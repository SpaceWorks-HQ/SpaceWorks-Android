package org.spaceworks.app.account

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.spaceworks.app.auth.AuthBundle
import org.spaceworks.app.auth.AuthGateway
import org.spaceworks.app.auth.AuthStateHolder
import org.spaceworks.app.member.MemberScope
import org.spaceworks.app.network.MemoryTokenStore
import org.spaceworks.app.network.TokenPair
import org.spaceworks.generated.model.*

/** Controller orchestration only: no Android runtime, transport, SDK or initialized AuthGateway. */
class AccountManagementCoordinatorTest {
    private val scope = MemberScope(7, "workshop")

    @Test fun coordinatorsSharingCredentialsKeepGatewayAndNativeCallbacksControllerLocal() = runTest {
        val store = installedStore()
        val firstCoordinator = AccountManagementCoordinator()
        val secondCoordinator = AccountManagementCoordinator()
        val first = Fixture(firstCoordinator, store)
        val second = Fixture(secondCoordinator, store)
        firstCoordinator.show(first.bundle); secondCoordinator.show(second.bundle)
        val firstHolder = first.openDevices()
        val secondHolder = second.openDevices()
        assertNotSame(firstHolder, secondHolder)
        assertNotSame(first.gateway, second.gateway)
        assertEquals(1, first.factories); assertEquals(1, second.factories)
        assertEquals(1, firstHolder.state.value.grants.size)
        assertEquals(1, secondHolder.state.value.grants.size)

        first.context.onPasswordChanged()
        assertEquals(1, first.foregrounds); assertEquals(0, second.foregrounds)
        second.context.onMembershipChanged()
        assertEquals(1, first.foregrounds); assertEquals(1, second.foregrounds)
        first.context.onConfirmedMembershipLeft(scope)
        assertEquals(listOf(scope.id), first.left)
        assertTrue(second.left.isEmpty())
        assertEquals(2, first.foregrounds); assertEquals(1, second.foregrounds)
        first.context.onSignedOut()
        assertEquals(1, first.ends); assertEquals(0, second.ends)
        assertNotNull(firstCoordinator.owner.completion.value)
        assertNull(secondCoordinator.owner.completion.value)
        // A shared-store end legitimately invalidates both identities, without invoking the
        // other controller's callbacks or publishing the first controller's completion there.
        assertFalse(second.context.hasSession())
        second.context.onPasswordChanged()
        assertEquals(1, second.foregrounds)
    }

    @Test fun bundleReplacementClearsPrivateHolderBeforeFactoryAndRejectsAllStaleOwners() = runTest {
        val coordinator = AccountManagementCoordinator()
        val first = Fixture(coordinator, installedStore(), "https://first.invalid/")
        val second = Fixture(coordinator, installedStore(), "https://second.invalid/")
        coordinator.show(first.bundle)
        val oldHolder = first.openDevices()
        coordinator.showing(oldHolder); assertTrue(coordinator.protectsNavigation())
        second.beforeFactory = {
            assertNull(oldHolder.state.value.scope)
            assertTrue(oldHolder.state.value.grants.isEmpty())
            assertFalse(coordinator.protectsNavigation())
        }
        coordinator.show(second.bundle)
        assertEquals("Replacement gateway must remain lazy", 0, second.factories)
        val newHolder = second.openDevices()
        coordinator.showing(newHolder)
        assertNotSame(oldHolder, newHolder)

        coordinator.hidden(oldHolder)
        coordinator.scopeChanged(first.runtime)
        coordinator.sessionEnded(first.runtime)
        coordinator.forget(first.bundle)
        first.context.onMembershipChanged()
        first.context.onPasswordChanged()
        first.context.onConfirmedMembershipLeft(scope)
        first.context.onSignedOut()
        first.context.onSecurityEnded(listOf("fixture-old-code"), "Old completion")

        assertSame(newHolder, coordinator.owner.current())
        assertEquals(scope, newHolder.state.value.scope)
        assertEquals(1, newHolder.state.value.grants.size)
        assertTrue(coordinator.protectsNavigation())
        assertNull(coordinator.owner.completion.value)
        assertEquals(0, first.foregrounds + first.ends + second.foregrounds + second.ends)
        assertTrue(first.left.isEmpty()); assertTrue(second.left.isEmpty())
        second.context.onPasswordChanged()
        assertEquals(1, second.foregrounds)
    }

    @Test fun onlyCurrentOverlayBlocksNavigationAndScopeAccountOrOriginClearReleasesIt() = runTest {
        val coordinator = AccountManagementCoordinator()
        val fixture = Fixture(coordinator, installedStore())
        coordinator.show(fixture.bundle)
        val holder = fixture.openDevices()
        val unrelated = AccountManagementStateHolder(OwnerFakeAccountGateway())
        coordinator.showing(unrelated); assertFalse(coordinator.protectsNavigation())
        coordinator.showing(holder); assertTrue(coordinator.protectsNavigation())
        coordinator.hidden(unrelated); assertTrue(coordinator.protectsNavigation())
        coordinator.hidden(holder); assertFalse(coordinator.protectsNavigation())
        coordinator.showing(holder); assertTrue(coordinator.protectsNavigation())

        coordinator.scopeChanged(fixture.runtime)
        assertFalse(coordinator.protectsNavigation())
        assertTrue(holder.state.value.grants.isEmpty()); assertNull(holder.state.value.scope)
        assertSame("Scope clear retains this account's holder binding", holder, coordinator.owner.current())
        fixture.selected = MemberScope(8, "other")
        holder.activate(requireNotNull(fixture.selected)); holder.open(AccountPage.DEVICES)
        coordinator.showing(holder); assertTrue(coordinator.protectsNavigation())
        coordinator.sessionEnded(fixture.runtime)
        assertFalse(coordinator.protectsNavigation())
        assertTrue(holder.state.value.grants.isEmpty()); assertNull(holder.state.value.scope)

        val replacement = fixture.openDevices()
        assertNotSame(holder, replacement)
        coordinator.showing(replacement); assertTrue(coordinator.protectsNavigation())
        coordinator.show(null)
        assertFalse(coordinator.protectsNavigation()); assertNull(coordinator.owner.current())
        assertTrue(replacement.state.value.grants.isEmpty())
    }

    private fun installedStore() = MemoryTokenStore().apply { replace(TokenPair("access", "refresh")) }

    private inner class Fixture(
        private val coordinator: AccountManagementCoordinator,
        private val tokens: MemoryTokenStore,
        origin: String = "https://workshop.invalid/",
    ) {
        private val accepted = tokens.snapshot().epoch
        private var user: String? = "41"
        var selected: MemberScope? = scope
        var foregrounds = 0; var ends = 0; var factories = 0
        val left = mutableListOf<Int>()
        val gateway = OwnerFakeAccountGateway()
        lateinit var context: AccountManagementContext
        var beforeFactory: () -> Unit = {}
        lateinit var runtime: NativeAccountRuntime
            private set
        val bundle: AuthBundle

        init {
            runtime = NativeAccountRuntime(origin, tokens, { accepted }, { user }, { selected },
                {
                    ends++
                    coordinator.sessionEnded(runtime)
                    user = null
                    tokens.replace(null)
                    true
                }, { foregrounds++ }, { left += it; true })
            bundle = AuthBundle(AuthStateHolder(UnusedAuthGateway, tokens), tokens, origin = origin)
            coordinator.register(bundle, runtime) { value ->
                beforeFactory(); factories++; context = value; gateway
            }
        }

        suspend fun openDevices() = requireNotNull(coordinator.owner.current()).also {
            it.activate(requireNotNull(selected)); it.open(AccountPage.DEVICES)
        }
    }

    private object UnusedAuthGateway : AuthGateway {
        private fun unexpected(): Nothing = error("Coordinator test must not call authentication transport")
        override suspend fun config(): PublicConfig = unexpected()
        override suspend fun login(username: String, password: String): DeviceLoginResult = unexpected()
        override suspend fun enroll(challenge: String): MFAEnrollment = unexpected()
        override suspend fun verify(challenge: String, code: String, enrollment: Boolean): MFACompleted = unexpected()
        override suspend fun me(): AuthUserPayload = unexpected()
        override suspend fun logout(): Unit = unexpected()
    }
}
