package com.example.data.sync

import android.content.Context
import android.provider.ContactsContract
import com.example.data.local.AppDatabase
import com.example.data.local.ContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsSyncManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    suspend fun syncContacts(): List<ContactEntity> = withContext(Dispatchers.IO) {
        val contactsList = mutableListOf<ContactEntity>()

        try {
            val emailMap = mutableMapOf<String, String>()
            // Query emails first
            val emailCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ),
                null,
                null,
                null
            )
            emailCursor?.use { ec ->
                val cidIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val addrIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (ec.moveToNext()) {
                    val cid = if (cidIdx >= 0) ec.getString(cidIdx) ?: "" else ""
                    val email = if (addrIdx >= 0) ec.getString(addrIdx) ?: "" else ""
                    if (cid.isNotEmpty() && email.isNotEmpty() && !emailMap.containsKey(cid)) {
                        emailMap[cid] = email
                    }
                }
            }

            // Query contacts with phones
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                val processedContactIds = mutableSetOf<String>()

                while (c.moveToNext()) {
                    val cid = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Unknown Contact" else "Unknown Contact"
                    val phone = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                    val email = emailMap[cid] ?: ""

                    val key = "$cid-$phone"
                    if (!processedContactIds.contains(key)) {
                        processedContactIds.add(key)
                        contactsList.add(
                            ContactEntity(
                                contactId = cid.ifEmpty { "CONTACT_${contactsList.size + 1}" },
                                displayName = name,
                                primaryPhone = phone,
                                secondaryPhone = "",
                                email = email,
                                lastModified = System.currentTimeMillis(),
                                isSynced = true
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            // Permission not granted or empty provider
        }

        db.contactDao().clearAll()
        if (contactsList.isNotEmpty()) {
            db.contactDao().insertAllContacts(contactsList)
        }
        contactsList
    }
}
