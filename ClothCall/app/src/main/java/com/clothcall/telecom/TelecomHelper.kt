package com.clothcall.telecom

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

private const val TAG = "ClothCall_Telecom"
private const val ACCOUNT_ID = "clothcall_self_managed"

object TelecomHelper {

    const val EXTRA_CAREGIVER_NAME = "caregiver_name"

    @Volatile
    var telecomAvailable: Boolean = true

    var activeConnection: ClothCallConnection? = null

    fun registerAccount(context: Context) {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val account = PhoneAccount.builder(handle(context), "ClothCall")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(account)
            Log.d(TAG, "PhoneAccount registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerAccount failed — calls will use in-app audio only", e)
        }
    }

    /**
     * Creates a system-level incoming call. The telecom framework routes audio
     * through the earpiece automatically once the connection is answered.
     * Falls back silently if the device or OS blocks self-managed calls.
     */
    fun startIncomingCall(context: Context, caregiverName: String) {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val extras = Bundle().apply {
                putString(EXTRA_CAREGIVER_NAME, caregiverName)
            }
            telecomManager.addNewIncomingCall(handle(context), extras)
            telecomAvailable = true
            Log.d(TAG, "addNewIncomingCall → $caregiverName")
        } catch (e: SecurityException) {
            telecomAvailable = false
            Log.e(TAG, "startIncomingCall blocked by security policy — using in-app audio only", e)
        } catch (e: Exception) {
            telecomAvailable = false
            Log.e(TAG, "startIncomingCall failed — no system call created", e)
        }
    }

    fun answerCall() {
        activeConnection?.onAnswer()
    }

    fun endCall() {
        activeConnection?.disconnect()
    }

    private fun handle(context: Context) = PhoneAccountHandle(
        ComponentName(context, ClothCallConnectionService::class.java),
        ACCOUNT_ID
    )
}
