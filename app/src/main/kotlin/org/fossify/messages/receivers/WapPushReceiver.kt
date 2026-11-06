package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.android.mms.transaction.PushReceiver
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.shouldUnarchive
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.updateConversationArchivedStatus
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.models.Message

/**
 * Intercepts incoming WAP_PUSH_DELIVER broadcasts before [PushReceiver].
 * When [org.fossify.messages.helpers.Config.blockMms] is enabled, the raw MMS PDU byte array is
 * never parsed and no MMSC network connection is initiated; instead a placeholder entry
 * "(MMS engellendi)" is recorded in the message history.
 */
class WapPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION
        ) {
            return
        }

        val appContext = context.applicationContext
        if (!appContext.config.blockMms) {
            PushReceiver().onReceive(context, intent)
            return
        }

        val pending = goAsync()
        ensureBackgroundThread {
            try {
                // Use only the originating address provided by the Android Telephony WapPushOverSms
                // dispatcher without parsing the WAP push PDU payload ("data" byte array).
                val rawAddress = intent.getStringExtra("address")
                    ?: intent.getStringExtra("sender")
                    ?: ""
                val address = rawAddress.trim().ifEmpty { "MMS" }

                if (address != "MMS") {
                    if (appContext.isNumberBlocked(address)) return@ensureBackgroundThread
                    if (appContext.baseConfig.blockUnknownNumbers) {
                        val privateCursor = appContext.getMyContactsCursor(
                            favoritesOnly = false,
                            withPhoneNumbersOnly = true
                        )
                        val result = SimpleContactsHelper(appContext).existsSync(address, privateCursor)
                        if (result == ContactLookupResult.NotFound) return@ensureBackgroundThread
                    }
                }

                val body = appContext.getString(R.string.mms_blocked_placeholder)
                val date = System.currentTimeMillis()
                val threadId = appContext.getThreadId(address)
                val subscriptionId = intent.getIntExtra("subscription", -1)
                val status = Telephony.Sms.STATUS_NONE
                val type = Telephony.Sms.MESSAGE_TYPE_INBOX

                val photoUri = SimpleContactsHelper(appContext).getPhotoUriFromPhoneNumber(address)
                val bitmap = appContext.getNotificationBitmap(photoUri)

                val newMessageId = appContext.insertNewSMS(
                    address = address,
                    subject = "",
                    body = body,
                    date = date,
                    read = 0,
                    threadId = threadId,
                    type = type,
                    subscriptionId = subscriptionId
                )

                appContext.getConversations(threadId).firstOrNull()?.let { conv ->
                    runCatching { appContext.insertOrUpdateConversation(conv) }
                }

                val senderName = appContext.getMyContactsCursor(
                    favoritesOnly = false,
                    withPhoneNumbersOnly = true
                ).use {
                    appContext.getNameFromAddress(address, it)
                }

                val participant = SimpleContact(
                    rawId = 0,
                    contactId = 0,
                    name = senderName,
                    photoUri = photoUri,
                    phoneNumbers = arrayListOf(
                        PhoneNumber(
                            value = address,
                            type = 0,
                            label = "",
                            normalizedNumber = address
                        )
                    ),
                    birthdays = ArrayList(),
                    anniversaries = ArrayList()
                )

                val message = Message(
                    id = newMessageId,
                    body = body,
                    type = type,
                    status = status,
                    participants = arrayListOf(participant),
                    date = (date / 1000).toInt(),
                    read = false,
                    threadId = threadId,
                    isMMS = false,
                    attachment = null,
                    senderPhoneNumber = address,
                    senderName = senderName,
                    senderPhotoUri = photoUri,
                    subscriptionId = subscriptionId
                )

                appContext.messagesDB.insertOrUpdate(message)

                if (appContext.shouldUnarchive()) {
                    appContext.updateConversationArchivedStatus(threadId, false)
                }

                refreshMessages()
                refreshConversations()
                appContext.showReceivedMessageNotification(
                    messageId = newMessageId,
                    isMms = false,
                    address = address,
                    senderName = senderName,
                    body = body,
                    threadId = threadId,
                    bitmap = bitmap
                )
            } finally {
                pending.finish()
            }
        }
    }
}
