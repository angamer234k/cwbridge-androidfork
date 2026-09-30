#!/usr/bin/env python3
"""Fix Check for updates crash: AlertDialog must not use applicationContext."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = ROOT / "app/src/main/java/com/cwbridge/android/UpdateChecker.kt"
    t = p.read_text()

    if "fun dialogUi()" not in t:
        anchor = """    private fun onMain(block: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else handler.post(block)
    }"""
        if anchor not in t:
            raise SystemExit("onMain not found")
        helper = """

    /** Prefer Activity for dialogs — applicationContext has no window token. */
    private fun dialogUi(): android.content.Context {
        val act = context as? android.app.Activity
        if (act != null && !act.isFinishing) return act
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity && !c.isFinishing) return c
            c = c.baseContext
        }
        return context
    }

    private fun showSafeDialog(
        title: String,
        message: String,
        positive: String? = null,
        onPositive: (() -> Unit)? = null,
    ) {
        onMain {
            try {
                val ui = dialogUi()
                if (ui !is android.app.Activity) {
                    android.widget.Toast.makeText(
                        context,
                        ("$title: $message").take(180),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    onPositive?.invoke()
                    return@onMain
                }
                val b = android.app.AlertDialog.Builder(ui)
                    .setTitle(title)
                    .setMessage(message)
                if (positive != null && onPositive != null) {
                    b.setPositiveButton(positive) { _, _ -> onPositive.invoke() }
                    b.setNegativeButton("Cancel", null)
                } else {
                    b.setPositiveButton("OK", null)
                }
                b.show()
            } catch (t: Throwable) {
                LogBuffer.e("UpdateChecker", "dialog failed: ${t.message}")
                try {
                    android.widget.Toast.makeText(
                        context,
                        ("$title — ${t.message}").take(160),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                } catch (_: Throwable) { }
            }
        }
    }"""
        t = t.replace(anchor, anchor + helper, 1)
        print("inserted dialogUi + showSafeDialog")
    else:
        print("helpers already present")

    start = t.find("    fun showUpdateDialog")
    end = t.find("    /** Run [block] on the main thread", start)
    if start < 0:
        raise SystemExit("showUpdateDialog missing")
    if end < 0:
        end = t.find("    private fun onMain", start)
    block = t[start:end]
    if "showSafeDialog" not in block:
        on = block.find("        onMain {")
        if on < 0:
            raise SystemExit("onMain in showUpdateDialog missing")
        head = block[:on]
        new_block = head + (
            "        showSafeDialog(\n"
            '            title = "Update available",\n'
            "            message = message,\n"
            '            positive = "Download",\n'
            "            onPositive = { requestDownloadPermission(apkAsset) },\n"
            "        )\n"
            "    }\n\n"
        )
        t = t[:start] + new_block + t[end:]
        print("showUpdateDialog → showSafeDialog")
    else:
        print("showUpdateDialog already safe")

    old_req = """    private fun requestDownloadPermission(apkAsset: GitHubAsset) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Download Update")
            .setMessage("Download ${apkAsset.name}? This will use your mobile data.")
            .setPositiveButton("Confirm Download") { _, _ ->
                downloadAndInstall(apkAsset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }"""
    new_req = """    private fun requestDownloadPermission(apkAsset: GitHubAsset) {
        showSafeDialog(
            title = "Download Update",
            message = "Download ${apkAsset.name}? This will use your mobile data.",
            positive = "Confirm Download",
            onPositive = { downloadAndInstall(apkAsset) },
        )
    }"""
    if old_req in t:
        t = t.replace(old_req, new_req, 1)
        print("requestDownloadPermission fixed")
    elif "requestDownloadPermission" in t and "showSafeDialog" in t[
        t.find("requestDownloadPermission"): t.find("requestDownloadPermission") + 400
    ]:
        print("requestDownloadPermission already safe")
    else:
        print("WARN requestDownloadPermission not patched")

    needle = 'android.app.AlertDialog.Builder(context)\n                    .setTitle("Download started")'
    if needle in t:
        idx = t.find(needle)
        on = t.rfind("onMain {", 0, idx)
        show = t.find(".show()", idx)
        end_brace = t.find("\n            }", show)
        if on < 0 or end_brace < 0:
            raise SystemExit("download started block bounds fail")
        end_brace = end_brace + len("\n            }")
        new_dl = (
            "showSafeDialog(\n"
            '                title = "Download started",\n'
            '                message = "The update will appear in your notifications.\\n" +\n'
            '                    "Tap it to install once the download finishes.",\n'
            "            )"
        )
        t = t[:on] + new_dl + t[end_brace:]
        print("download-started dialog fixed")
    else:
        print("download-started already fixed or missing")

    left = t.count("AlertDialog.Builder(context)")
    print("remaining Builder(context)", left)
    if left:
        t = t.replace("android.app.AlertDialog.Builder(context)", "android.app.AlertDialog.Builder(dialogUi())")
        print("fallback: Builder(dialogUi()) for leftovers")

    p.write_text(t)
    print("UpdateChecker", p.stat().st_size)

    mp = ROOT / "app/src/main/java/com/cwbridge/android/MainActivity.kt"
    mt = mp.read_text()
    if "UpdateChecker(applicationContext)" in mt:
        mt = mt.replace("UpdateChecker(applicationContext)", "UpdateChecker(this@MainActivity)")
        mp.write_text(mt)
        print("MainActivity: UpdateChecker → Activity")
    else:
        print("MainActivity: no applicationContext UpdateChecker")

    print("hotfix update OK")


if __name__ == "__main__":
    main()
