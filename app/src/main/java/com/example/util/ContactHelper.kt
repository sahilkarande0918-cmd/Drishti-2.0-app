package com.example.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactHelper {
    data class ContactDetails(
        val name: String,
        val phone: String,
        val email: String
    )

    fun getContactDetails(context: Context, contactUri: Uri): ContactDetails? {
        val contentResolver = context.contentResolver
        var name = ""
        var phone = ""
        var email = ""

        // 1. Get Contact ID & Display Name
        var contactId: String? = null
        contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: ""
                }
                val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                if (idIndex >= 0) {
                    contactId = cursor.getString(idIndex)
                }
            }
        }

        if (contactId == null) return null

        // 2. Query Phone Number
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                val phoneIndex = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (phoneIndex >= 0) {
                    phone = phoneCursor.getString(phoneIndex) ?: ""
                }
            }
        }

        // 3. Query Email
        contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            null,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
            arrayOf(contactId),
            null
        )?.use { emailCursor ->
            if (emailCursor.moveToFirst()) {
                val emailIndex = emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                if (emailIndex >= 0) {
                    email = emailCursor.getString(emailIndex) ?: ""
                }
            }
        }

        return ContactDetails(
            name = name.trim(),
            phone = phone.replace("\\s".toRegex(), "").trim(), // strip spaces
            email = email.trim()
        )
    }
}
