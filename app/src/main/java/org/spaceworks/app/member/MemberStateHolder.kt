package org.spaceworks.app.member

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.spaceworks.app.network.ApiFailure
import java.util.concurrent.atomic.AtomicLong

data class MemberLoad<T>(val value:T?=null,val loading:Boolean=false,val error:String?=null)
data class MemberState(
    val scope:MemberScope?=null,val query:String="",val category:String?=null,val unread:Boolean=false,
    val inventory:MemberLoad<MemberPage<InventoryItem>> = MemberLoad(),
    val categories:MemberLoad<List<InventoryCategory>> = MemberLoad(),
    val overview:MemberLoad<MemberOverview> = MemberLoad(),val card:MemberLoad<MemberCard> = MemberLoad(),
    val inbox:MemberLoad<MemberPage<InboxItem>> = MemberLoad(),
    val events:MemberLoad<List<MemberEvent>> = MemberLoad(),val spaces:MemberLoad<List<MemberSpace>> = MemberLoad(),
    val product:MemberLoad<InventoryItem> = MemberLoad(),val productId:String?=null,
    val inboxBusy:Boolean=false,val bookingMoreBusy:Boolean=false,val notice:String?=null,val retryAtMillis:Long=0,
)
enum class MemberPreviewScenario(val label:String) {
    POPULATED("Populated"), EMPTY("Empty"), OFFLINE("Offline"), RESTRICTED("Access refused"),
    UNKNOWN_DUES("Dues unavailable"), REVOKED_CARD("Revoked card"), NO_CARD("Card not issued")
}
interface MemberPreviewControl {fun scenario(value:MemberPreviewScenario)}

/** Immutable screen state; all transport values come through a generated-client gateway. */
class MemberStateHolder(private val api:MemberGateway,private val now:()->Long=System::currentTimeMillis,
    private val onUnauthorized:suspend()->Unit={}) {
    private val mutable=MutableStateFlow(MemberState())
    val state=mutable.asStateFlow()
    private val epoch=AtomicLong()
    private val inventoryVersion=AtomicLong()
    private val inboxVersion=AtomicLong()
    private val productVersion=AtomicLong()
    private val overviewVersion=AtomicLong()
    private val mutation=Mutex()
    private val resourceVersions=java.util.concurrent.ConcurrentHashMap<String,AtomicLong>()
    val hasPreview get()=api is MemberPreviewControl
    fun clear() {epoch.incrementAndGet();mutable.value=MemberState()}
    suspend fun activate(scope:MemberScope) {
        if(state.value.scope==scope)return
        epoch.incrementAndGet();mutable.value=MemberState(scope=scope,retryAtMillis=state.value.retryAtMillis)
        loadAll()
    }
    suspend fun refreshAll() {if(state.value.scope!=null)loadAll()}
    private suspend fun loadAll()=supervisorScope {
        listOf(async {refreshOverview()},async {refreshInventory()},async {refreshCategories()},
            async {refreshCard()},async {refreshInbox()},async {refreshEvents()},async {refreshSpaces()}).awaitAll()
        Unit
    }
    suspend fun preview(scenario:MemberPreviewScenario) {
        val control=api as? MemberPreviewControl ?: return
        val scope=state.value.scope ?: return
        control.scenario(scenario);epoch.incrementAndGet();mutable.value=MemberState(scope=scope)
        loadAll()
    }
    private fun alive(expected:Long)=expected==epoch.get()
    private fun ready()=now()>=state.value.retryAtMillis
    private suspend fun failure(f:Exception,expected:Long):String {
        if(alive(expected) && f is ApiFailure && f.status==429)mutable.update {it.copy(retryAtMillis=now()+(f.retryAfterSeconds?:60)*1000)}
        if(alive(expected) && f is ApiFailure && f.status==401)onUnauthorized()
        return if(f is ApiFailure)f.detail else "Couldn't connect. Try again."
    }
    private suspend fun <T> fetch(key:String,read:(MemberState)->MemberLoad<T>,write:(MemberState,MemberLoad<T>)->MemberState,
        clear:Boolean=false,valid:()->Boolean={true},operation:suspend(MemberScope)->T) {
        val scope=state.value.scope ?: return
        val expected=epoch.get()
        val counter=resourceVersions.computeIfAbsent(key){AtomicLong()}
        val request=counter.incrementAndGet()
        fun current()=alive(expected) && valid() && request==counter.get()
        if(!ready()) {
            mutable.update {write(it,MemberLoad(if(clear)null else read(it).value,error="Try again after the timer finishes."))}
            return
        }
        mutable.update {write(it,MemberLoad(if(clear)null else read(it).value,true))}
        try {
            val value=operation(scope)
            if(current())mutable.update {write(it,MemberLoad(value))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){val text=failure(f,expected);if(current())mutable.update {write(it,MemberLoad(if(clear)null else read(it).value,error=text))}}
    }
    suspend fun refreshOverview() {
        overviewVersion.incrementAndGet();mutable.update {it.copy(bookingMoreBusy=false)}
        fetch("overview",{it.overview},{s,v->s.copy(overview=v)}){api.overview(it)}
    }
    suspend fun refreshCategories()=fetch("categories",{it.categories},{s,v->s.copy(categories=v)}){api.categories(it)}
    suspend fun refreshCard()=fetch("card",{it.card},{s,v->s.copy(card=v)},clear=true){scope->
        val card=api.card(scope)
        card.copy(qrImage=card.qrImage?.takeIf {card.isActive && card.qrActive && it.isNotBlank()})
    }
    suspend fun refreshEvents()=fetch("events",{it.events},{s,v->s.copy(events=v)}){api.publicEvents(it)}
    suspend fun refreshSpaces()=fetch("spaces",{it.spaces},{s,v->s.copy(spaces=v)}){api.spaces(it)}
    suspend fun search(query:String,category:String?=state.value.category) {
        mutable.update {it.copy(query=query,category=category,inventory=MemberLoad(),productId=null,product=MemberLoad())}
        refreshInventory()
    }
    suspend fun refreshInventory() {
        val version=inventoryVersion.incrementAndGet();val query=state.value.query;val category=state.value.category
        fetch("inventory",{it.inventory},{s,v->s.copy(inventory=v)},valid={version==inventoryVersion.get()}){api.inventory(it,query,category)}
    }
    suspend fun moreInventory() {
        val before=state.value.inventory
        val next=before.value?.next ?: return
        if(before.loading)return
        val version=inventoryVersion.get();val query=state.value.query;val category=state.value.category
        fetch("inventory",{it.inventory},{s,v->s.copy(inventory=v)},valid={version==inventoryVersion.get()}){scope->
            val page=api.inventory(scope,query,category,next)
            page.copy(items=(before.value.items+page.items).distinctBy {it.id})
        }
    }
    suspend fun selectProduct(id:String) {
        val version=productVersion.incrementAndGet()
        mutable.update {it.copy(productId=id,product=MemberLoad())}
        fetch("product",{it.product},{s,v->s.copy(product=v)},clear=true,valid={version==productVersion.get() && state.value.productId==id}){api.product(it,id)}
    }
    fun closeProduct(){productVersion.incrementAndGet();mutable.update {it.copy(productId=null,product=MemberLoad())}}
    suspend fun filterInbox(unread:Boolean) {
        inboxVersion.incrementAndGet();mutable.update {it.copy(unread=unread,inbox=MemberLoad())};refreshInbox()
    }
    suspend fun refreshInbox() {
        val version=inboxVersion.incrementAndGet();val unread=state.value.unread
        fetch("inbox",{it.inbox},{s,v->s.copy(inbox=v)},valid={version==inboxVersion.get()}){api.inbox(it,unread)}
    }
    suspend fun moreInbox() {
        val before=state.value.inbox;val next=before.value?.next ?: return
        if(before.loading)return
        val version=inboxVersion.get();val unread=state.value.unread
        fetch("inbox",{it.inbox},{s,v->s.copy(inbox=v)},valid={version==inboxVersion.get()}){scope->
            val page=api.inbox(scope,unread,next);page.copy(items=(before.value.items+page.items).distinctBy {it.id})
        }
    }
    suspend fun markRead(id:String)=inboxMutation {scope->api.markRead(scope,id)}
    suspend fun markAllRead()=inboxMutation {scope->api.markAllRead(scope)}
    private suspend fun inboxMutation(block:suspend(MemberScope)->Unit):Boolean {
        val scope=state.value.scope ?: return false
        if(!ready() || !mutation.tryLock())return false
        val expected=epoch.get();mutable.update {it.copy(inboxBusy=true,notice=null)}
        return try {block(scope);if(alive(expected)){refreshInbox();mutable.update {it.copy(notice="Inbox updated.")}};alive(expected)}
        catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){val text=failure(f,expected);if(alive(expected))mutable.update {it.copy(inbox=it.inbox.copy(error=text))};false}
        finally {if(alive(expected))mutable.update {it.copy(inboxBusy=false)};mutation.unlock()}
    }
    suspend fun moreBookings() {
        val scope=state.value.scope ?: return
        val overview=state.value.overview.value ?: return
        val next=overview.bookings.next ?: return
        if(!ready() || state.value.bookingMoreBusy)return
        val expected=epoch.get();val version=overviewVersion.get();mutable.update {it.copy(bookingMoreBusy=true)}
        try {
            val page=api.bookings(scope,next=next)
            if(alive(expected) && version==overviewVersion.get())mutable.update {s->s.copy(overview=s.overview.copy(value=s.overview.value?.copy(bookings=page.copy(items=(overview.bookings.items+page.items).distinctBy {it.id}))))}
        }catch(cancelled:CancellationException){throw cancelled}
        catch(f:Exception){val text=failure(f,expected);if(alive(expected) && version==overviewVersion.get())mutable.update {it.copy(overview=it.overview.copy(error=text))}}
        finally {if(alive(expected) && version==overviewVersion.get())mutable.update {it.copy(bookingMoreBusy=false)}}
    }
}
