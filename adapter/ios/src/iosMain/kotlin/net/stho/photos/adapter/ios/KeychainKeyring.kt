@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package net.stho.photos.adapter.ios

import kotlinx.cinterop.BetaInteropApi
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
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * §7's `Keyring`, over the iOS Keychain (§1).
 *
 * The same two items the CLI keeps, under the same `service photos-cli` with the field as the
 * account — so the concept is one concept across both devices even though nothing is shared
 * between them.
 *
 * **Both items are readable whenever the phone is unlocked, and only on this phone.**
 * `…WhenUnlockedThisDeviceOnly` keeps them out of backups and iCloud Keychain, and asks for no
 * passcode, fingerprint or face — §1 dropped the biometric gate, because it made a phone without
 * an enrolled finger unable to store the password at all (`errSecAuthFailed`, measured on an SE2).
 *
 * The three outcomes are the same three §1 turns the CLI's exit codes on, translated: a value,
 * *absent* when the Keychain answers and holds nothing, and *unavailable* when it will not
 * answer — the phone is locked, which is *not now* rather than a broken install.
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
            when (val status = SecItemCopyMatching(query.ref, found.ptr)) {
                errSecSuccess -> {
                    val data = CFBridgingRelease(found.value) as? NSData
                        ?: return@memScoped KeyringRead.Unavailable("the Keychain returned no data")
                    KeyringRead.Found(data.toByteArray().decodeToString())
                }

                errSecItemNotFound -> KeyringRead.Absent

                // The phone is locked. Nothing about the install is wrong and nothing needs
                // retyping -- ask again later.
                errSecInteractionNotAllowed ->
                    KeyringRead.Unavailable("unlock your phone to open your photo library")

                else -> KeyringRead.Unavailable("the Keychain refused the request (status $status)")
            }
        }
    }

    override fun write(field: String, secret: String) {
        // Replace rather than add: the attributes are the item's identity, and SecItemAdd on an
        // existing pair fails with errSecDuplicateItem rather than updating. It also replaces an
        // item an earlier build stored behind the biometric gate.
        remove(field)
        val bytes = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw CredentialFailure.KeyringProtocol("the secret is not valid UTF-8")
        CfQuery().use { query ->
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, field)
            query.putData(kSecValueData, bytes)
            query.put(kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
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
}
