#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch_layout():
    p = ROOT / "app/src/main/res/layout/activity_main.xml"
    t = p.read_text()
    if "btnRemoteToggle" in t:
        print("layout: remote already")
        return
    # Insert remote card before closing of categoryServer's first card is hard;
    # insert after btnServerQuota block end — find WEB SERVER card end
    marker = 'android:id="@+id/btnServerQuota"'
    if marker not in t:
        raise SystemExit("btnServerQuota missing")
    # Find the MaterialButton for quota and the following closing tags, then insert after the LinearLayout of the card
    # Simpler: after serverState TextView area - insert after btnRegenPassword if present
    insert_after = None
    for key in ["btnRegenPassword", "btnShowPassword", "btnServerQuota"]:
        if f'@+id/{key}' in t:
            insert_after = key
            break
    if not insert_after:
        raise SystemExit("no server buttons")

    block = '''

                        <TextView
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="16dp"
                            android:text="REMOTE (pair code)"
                            android:textAllCaps="true"
                            android:textColor="@color/muted"
                            android:textSize="11sp" />

                        <TextView
                            android:id="@+id/remoteState"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="6dp"
                            android:text="Remote off — uses cw-control.vercel.app"
                            android:textColor="@color/muted"
                            android:textSize="12sp" />

                        <TextView
                            android:id="@+id/remoteCode"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="8dp"
                            android:fontFamily="monospace"
                            android:textSize="28sp"
                            android:textStyle="bold"
                            android:textColor="@android:color/white"
                            android:letterSpacing="0.2"
                            android:text="" />

                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnRemoteToggle"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="10dp"
                            android:text="Start remote pair" />
'''

    # Append after the last server button's MaterialButton closing tag following insert_after
    idx = t.find(f'android:id="@+id/{insert_after}"')
    # find end of this MaterialButton element
    end_tag = t.find("/>", idx)
    if end_tag < 0:
        raise SystemExit("button end miss")
    end_tag += 2
    t = t[:end_tag] + block + t[end_tag:]
    p.write_text(t)
    print("layout: remote UI")


def patch_main():
    p = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    t = p.read_text()
    if "RemoteRelay" in t and "btnRemoteToggle" in t:
        print("MainActivity: remote already")
        return

    if "import com.cwbridge.android.bridge.RemoteRelay" not in t:
        # after BridgeControl import if any
        if "import com.cwbridge.android.bridge.BridgeControl" in t:
            t = t.replace(
                "import com.cwbridge.android.bridge.BridgeControl",
                "import com.cwbridge.android.bridge.BridgeControl\nimport com.cwbridge.android.bridge.RemoteRelay",
                1,
            )
        else:
            t = t.replace(
                "package com.cwbridge.android",
                "package com.cwbridge.android\n\nimport com.cwbridge.android.bridge.RemoteRelay",
                1,
            )

    if "btnRemoteToggle" not in t:
        hook = "        binding.btnServerQuota.setOnClickListener { setDefaultDomainQuota() }"
        add = hook + "\n        binding.btnRemoteToggle.setOnClickListener { toggleRemotePair() }\n        refreshRemoteUi()"
        if hook in t:
            t = t.replace(hook, add, 1)
        else:
            # try setupServerUi end
            if "refreshServerUi()" in t and "setupServerUi" in t:
                t = t.replace(
                    "        binding.btnServerToggle.setOnClickListener { toggleLocalServer() }",
                    "        binding.btnServerToggle.setOnClickListener { toggleLocalServer() }\n"
                    "        binding.btnRemoteToggle.setOnClickListener { toggleRemotePair() }",
                    1,
                )
                t = t.replace(
                    "        refreshServerUi()\n    }\n\n    private fun refreshServerUi()",
                    "        refreshServerUi()\n        refreshRemoteUi()\n    }\n\n    private fun refreshServerUi()",
                    1,
                )

    if "fun toggleRemotePair" not in t:
        methods = r'''

    private fun refreshRemoteUi() {
        try {
            val running = RemoteRelay.isRunning()
            binding.btnRemoteToggle.text = if (running) "Stop remote pair" else "Start remote pair"
            val code = RemoteRelay.code
            binding.remoteCode.text = code ?: ""
            binding.remoteState.text = when {
                !running -> "Remote off — ${RemoteRelay.DEFAULT_BASE}"
                code != null && RemoteRelay.status == "waiting" ->
                    "Code $code — open ${RemoteRelay.baseUrl} and enter it"
                else -> RemoteRelay.status
            }
        } catch (t: Throwable) {
            LogBuffer.w("UI", "refreshRemoteUi: ${t.message}")
        }
    }

    private fun toggleRemotePair() {
        if (RemoteRelay.isRunning()) {
            RemoteRelay.stop()
            refreshRemoteUi()
            Toast.makeText(this, "Remote stopped", Toast.LENGTH_SHORT).show()
            return
        }
        RemoteRelay.setBaseUrl(RemoteRelay.DEFAULT_BASE)
        RemoteRelay.start(lifecycleScope, applicationContext) { msg ->
            runOnUiThread {
                try {
                    binding.remoteState.text = msg
                    binding.remoteCode.text = RemoteRelay.code ?: ""
                    binding.btnRemoteToggle.text =
                        if (RemoteRelay.isRunning()) "Stop remote pair" else "Start remote pair"
                } catch (_: Throwable) {
                }
            }
        }
        refreshRemoteUi()
        Toast.makeText(this, "Remote starting…", Toast.LENGTH_SHORT).show()
    }
'''
        # insert before last closing of class
        last = t.rfind("\n}")
        if last < 0:
            raise SystemExit("class end miss")
        t = t[:last] + methods + t[last:]

    p.write_text(t)
    print("MainActivity: remote wired", p.stat().st_size)


def main():
    patch_layout()
    patch_main()
    print("hotfix remote UI OK")


if __name__ == "__main__":
    main()
