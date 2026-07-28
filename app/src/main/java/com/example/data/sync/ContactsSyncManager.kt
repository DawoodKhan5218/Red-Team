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
            // 1. Fetch Phone Numbers grouped by CONTACT_ID
            val phonesMap = mutableMapOf<String, MutableList<String>>()
            val phoneCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )
            phoneCursor?.use { pc ->
                val cidIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (pc.moveToNext()) {
                    val cid = if (cidIdx >= 0) pc.getString(cidIdx) ?: "" else ""
                    val num = if (numIdx >= 0) pc.getString(numIdx) ?: "" else ""
                    if (cid.isNotEmpty() && num.isNotEmpty()) {
                        val list = phonesMap.getOrPut(cid) { mutableListOf() }
                        if (!list.contains(num)) {
                            list.add(num)
                        }
                    }
                }
            }

            // 2. Fetch Emails grouped by CONTACT_ID
            val emailsMap = mutableMapOf<String, MutableList<String>>()
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
                    val addr = if (addrIdx >= 0) ec.getString(addrIdx) ?: "" else ""
                    if (cid.isNotEmpty() && addr.isNotEmpty()) {
                        val list = emailsMap.getOrPut(cid) { mutableListOf() }
                        if (!list.contains(addr)) {
                            list.add(addr)
                        }
                    }
                }
            }

            // 3. Fetch Notes grouped by CONTACT_ID
            val notesMap = mutableMapOf<String, String>()
            try {
                val noteCursor = context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Data.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Note.NOTE
                    ),
                    "${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE),
                    null
                )
                noteCursor?.use { nc ->
                    val cidIdx = nc.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                    val noteIdx = nc.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
                    while (nc.moveToNext()) {
                        val cid = if (cidIdx >= 0) nc.getString(cidIdx) ?: "" else ""
                        val note = if (noteIdx >= 0) nc.getString(noteIdx) ?: "" else ""
                        if (cid.isNotEmpty() && note.isNotEmpty()) {
                            notesMap[cid] = note
                        }
                    }
                }
            } catch (_: Exception) {}

            // 4. Query Main Contacts table (ContactsContract.Contacts)
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.STARRED
                ),
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val starredIdx = c.getColumnIndex(ContactsContract.Contacts.STARRED)

                val processedCids = mutableSetOf<String>()

                while (c.moveToNext()) {
                    val cid = if (idIdx >= 0) c.getString(idIdx) ?: "" else ""
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "Unknown Contact" else "Unknown Contact"
                    val starredInt = if (starredIdx >= 0) c.getInt(starredIdx) else 0

                    if (cid.isNotEmpty() && !processedCids.contains(cid)) {
                        processedCids.add(cid)

                        val phones = phonesMap[cid] ?: emptyList()
                        val emails = emailsMap[cid] ?: emptyList()
                        val note = notesMap[cid] ?: ""

                        contactsList.add(
                            ContactEntity(
                                contactId = cid,
                                displayName = name,
                                primaryPhone = phones.firstOrNull() ?: "",
                                secondaryPhone = if (phones.size > 1) phones[1] else "",
                                email = emails.firstOrNull() ?: "",
                                phoneNumbersJson = phones.joinToString("|||"),
                                emailsJson = emails.joinToString("|||"),
                                notes = note,
                                starred = (starredInt == 1),
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
