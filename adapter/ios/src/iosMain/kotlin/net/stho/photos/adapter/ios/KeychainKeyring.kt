@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.adapter.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import net.stho.photos.CredentialFailure
import net.stho.photos.ingest.Credentials
import net.stho.photos.ports.Keyring
import net.stho.photos.ports.KeyringRead
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecAuthFailed
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.errSecUserCanceled
import platform.Security.kSecAccessControlBiometryCurrentSet
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseOperationPrompt
import platform.Security.kSecValueData

/**
 * §7's `Keyring`, over the iOS Keychain (§1).
 *
 * The same two items the CLI keeps, under the same `service photos-cli` with the field as the
 * account — so the concept is one concept across both devices even though nothing is shared
 * between them.
 *
 * **Only the password is behind Face ID.** §1 puts the secret under
 * `kSecAccessControlBiometryCurrentSet`, and is equally explicit that the endpoint "is not a
 * secret — it is a URL". Gating the URL too would buy nothing and would mean two biometric
 * prompts to answer one question.
 *
 * `…BiometryCurrentSet` rather than `…BiometryAny` is the stricter of the two on purpose: it
 * invalidates the item when a face or fingerprint is added to the device, so someone who can
 * enrol their own biometrics cannot thereby read the library.
 *
 * The three outcomes are the same three §1 turns the CLI's exit codes on, translated: a value,
 * *absent* when the Keychain answers and holds nothing, and *unavailable* when it will not
 * answer — a declined or unavailable Face ID, which is *not now* rather than a broken install.
 *
 * **What a simulator can and cannot show.** Storing and reading both items round-trips there,
 * biometric access control included — measured. What it does *not* do is enforce the gate: with
 * no passcode and no enrolled face there is nothing to prompt for, so a read succeeds silently.
 * The protection itself is therefore a device-only claim, unlike the storage.
 *
 * Every call needs the app to have an `application-identifier`, which comes from being signed
 * with an entitlements file. Unsigned, all three fail with -34018 while the rest of the app runs
 * perfectly — which is why `Scripts/ios-sim.sh` signs ad-hoc rather than passing
 * `CODE_SIGNING_ALLOWED=NO`.
 */
public class KeychainKeyring(
    private val service: String = Credentials.SERVICE,
) : Keyring {

    override fun read(field: String): KeyringRead = memScoped {
        val found = alloc<CFTypeRefVar>()
        CfQuery().use { query ->
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, field)
            query.put(kSecReturnData, kCFBooleanTrue)
            query.put(kSecMatchLimit, kSecMatchLimitOne)
            if (field == biometric) {
                // Shown on the Face ID sheet, so the reason a prompt appeared is legible.
                query.putString(kSecUseOperationPrompt, "Unlock your photo library")
            }
            when (val status = SecItemCopyMatching(query.ref, found.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(found.value) as? NSData
                        ?: return@memScoped KeyringRead.Unavailable("the Keychain returned no data")
                    KeyringRead.Found(data.toByteArray().decodeToString())
                }

                errSecItemNotFound -> KeyringRead.Absent

                // Face ID declined or unavailable, or the device is locked. Nothing about the
                // install is wrong and nothing needs retyping -- ask again later.
                errSecInteractionNotAllowed, errSecUserCanceled, errSecAuthFailed ->
                    KeyringRead.Unavailable("Face ID is needed to unlock your photo library")

                else -> KeyringRead.Unavailable("the Keychain refused the request (status $status)")
            }
        }
    }

    override fun write(field: String, secret: String) {
        // Replace rather than add: the attributes are the item's identity, and SecItemAdd on an
        // existing pair fails with errSecDuplicateItem rather than updating.
        remove(field)
        val bytes = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw CredentialFailure.KeyringProtocol("the secret is not valid UTF-8")
        CfQuery().use { query ->
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, field)
            query.putData(kSecValueData, bytes)
            if (field == biometric) query.put(kSecAttrAccessControl, query.own(accessControl()))
            val status = SecItemAdd(query.ref, null)
            if (status != errSecSuccess) {
                throw CredentialFailure.KeyringUnavailable(
                    "the Keychain would not store the $field (status $status)",
                )
            }
        }
    }

    override fun remove(field: String) {
        CfQuery().use { query ->
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, field)
            // Removing nothing is not an error: the gesture means *make sure it is gone*.
            val status = SecItemDelete(query.ref)
            if (status != errSecSuccess && status != errSecItemNotFound) {
                throw CredentialFailure.KeyringUnavailable(
                    "the Keychain would not remove the $field (status $status)",
                )
            }
        }
    }

    /** §1's access control: this device only, and invalidated if the enrolled set changes. */
    private fun accessControl(): COpaquePointer = SecAccessControlCreateWithFlags(
        allocator = null,
        protection = kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly,
        flags = kSecAccessControlBiometryCurrentSet,
        error = null,
    ) ?: throw CredentialFailure.KeyringUnavailable(
        "this device cannot protect a password with Face ID; set a passcode and enrol a face",
    )

    private val biometric: String get() = Credentials.Field.PASSWORD.attribute
}
