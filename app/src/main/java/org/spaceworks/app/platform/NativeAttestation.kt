package org.spaceworks.app.platform

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Base64

/** Produces an opaque Google proof only; the generated auth gateway owns the server envelope. */
fun interface IntegrityTokenEngine {
    suspend fun token(cloudProjectNumber: Long, requestHash: String): String
}

class PlayIntegrityProofProvider(
    private val cloudProjectNumber: Long?,
    private val engine: IntegrityTokenEngine,
) {
    constructor(context: Context, cloudProjectNumber: Long?) : this(cloudProjectNumber, GoogleIntegrityTokenEngine(context))

    suspend fun proof(challenge: String): String {
        val project = cloudProjectNumber?.takeIf { it > 0 }
            ?: throw PlatformUnavailable("Device verification is not configured. Contact your makerspace.")
        require(challenge.isNotBlank()) { "A fresh sign-in challenge is required." }
        val proof = engine.token(project, requestHash(challenge))
        check(proof.isNotBlank()) { "The device did not return a verification proof. Try again." }
        return proof
    }

    companion object {
        /** PRD: base64url(SHA-256(challenge UTF-8)), without padding or line wrapping. */
        fun requestHash(challenge: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(challenge.toByteArray(Charsets.UTF_8)))
    }
}

private class GoogleIntegrityTokenEngine(context: Context) : IntegrityTokenEngine {
    private val manager = IntegrityManagerFactory.createStandard(context.applicationContext)
    private val preparation = Mutex()
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private var preparedProject: Long? = null

    override suspend fun token(cloudProjectNumber: Long, requestHash: String): String {
        try {
            val ready = preparation.withLock {
                if (provider == null || preparedProject != cloudProjectNumber) {
                    provider = manager.prepareIntegrityToken(
                        StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                            .setCloudProjectNumber(cloudProjectNumber).build(),
                    ).awaitPlatformTask()
                    preparedProject = cloudProjectNumber
                }
                checkNotNull(provider)
            }
            return ready.request(StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(requestHash).build()).awaitPlatformTask().token()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (failure is StandardIntegrityException && failure.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                preparation.withLock { provider = null; preparedProject = null }
            }
            // Never silently substitute development attestation or retry a consumed server challenge.
            throw PlatformUnavailable("This device could not verify the app. Try again.")
        }
    }
}
