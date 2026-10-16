package com.galaxy.assettrail.driver.device

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads asset tags by NFC while a check-in is waiting for a scan. The tag's UID is what the trip
 * plan records ("04:A3:7E:91"). Cryptographic tags (NTAG 424 DNA, spec §9) would add a per-tap
 * authentication message here.
 */
class NfcTagReader(context: Context) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    private val _tags = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val tags: SharedFlow<String> = _tags.asSharedFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun setListening(listening: Boolean) {
        _listening.value = listening
    }

    /** Called by the activity while resumed. */
    fun enable(activity: Activity) {
        adapter?.enableReaderMode(
            activity,
            { tag: Tag -> _tags.tryEmit(formatUid(tag.id)) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    fun disable(activity: Activity) {
        adapter?.disableReaderMode(activity)
    }

    /** Lets the demo controls act as a tag. */
    fun simulateScan(uid: String) {
        _tags.tryEmit(uid)
    }

    companion object {
        fun formatUid(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }
    }
}
