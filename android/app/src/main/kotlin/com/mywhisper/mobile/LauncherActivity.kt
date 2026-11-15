package com.mywhisper.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keep the launch alias out of MainActivity's task so changing icons cannot remove it. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        finish()
    }
}
