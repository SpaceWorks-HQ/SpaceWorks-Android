package org.spaceworks.app.platform

internal enum class FeedbackKind { SELECTION, CONFIRM, REJECT }
internal class FeedbackTicket internal constructor(internal val owner:Any)

/** One explicit UI action, consumed once; disposal/owner changes discard pending feedback. */
internal class FeedbackTicketGate {
    private var pending:FeedbackTicket?=null
    fun begin(owner:Any)=FeedbackTicket(owner).also {pending=it}
    fun cancel(){pending=null}
    fun finish(ticket:FeedbackTicket,owner:Any,eligible:Boolean):Boolean {
        if(pending!==ticket)return false
        pending=null
        return ticket.owner==owner && eligible
    }
}

