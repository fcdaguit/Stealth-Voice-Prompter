package com.voiceprompter

import android.app.Application
import com.voiceprompter.data.AppDatabase

class VoicePrompterApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.get(this) }
}
