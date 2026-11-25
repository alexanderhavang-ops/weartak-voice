package se.takcampus.voice.watch

import android.content.Context
import se.takcampus.voice.VoicePackageImporter
import se.takcampus.voice.VoiceProfile

/** Keeps local voice selection separate from the encrypted Mumble login. */
internal object LocalVoiceSettings {
    private fun preferences(context: Context) = context.getSharedPreferences("voice-local", Context.MODE_PRIVATE)
    fun load(context: Context): VoiceProfile? = preferences(context).getString("channel", null)?.let {
        VoicePackageImporter.fromJson(it.toByteArray(Charsets.UTF_8)).channels.single().profile()
    }
    fun callsign(context: Context): String = preferences(context).getString("callsign", "WearTAK") ?: "WearTAK"
    fun mumbleGuestName(context: Context): String {
        val name = callsign(context).trim()
        if (name.isNotEmpty() && name != "WearTAK") return name
        return "WearTAK-" + uid(context).take(8)
    }
    fun select(context: Context, channel: VoiceProfile?, callsign: String = callsign(context)) {
        require(channel == null || channel.multicast())
        val entry = channel?.let { String(VoicePackageImporter.portableJson(listOf(it), it.label()), Charsets.UTF_8) }
        check(preferences(context).edit().putString("channel", entry)
            .putString("callsign", VoiceProfile.name(callsign, "Callsign")).commit())
    }
    fun uid(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString("uid", null) ?: java.util.UUID.randomUUID().toString().also {
            preferences.edit().putString("uid", it).apply()
        }
    }
}
