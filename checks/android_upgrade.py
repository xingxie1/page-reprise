"""Exercise the installed app's real public feed and system installer on a disposable emulator."""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "app.pagereprise"


def adb(*args, timeout=25):
    return subprocess.check_output(["adb", *args], text=True, timeout=timeout)


def version():
    package = adb("shell", "dumpsys", "package", PACKAGE)
    return re.search(r"versionName=(\S+)", package).group(1)


def visible_nodes():
    if "dumped to" not in adb("shell", "uiautomator", "dump", "/sdcard/reprise-upgrade.xml", timeout=10):
        return []
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/reprise-upgrade.xml")).iter("node"))


def tap(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def confirm_update(nodes):
    for node in nodes:
        owner = node.get("package", "")
        label = node.get("text", "").strip().lower()
        if owner == PACKAGE and label == "打开系统设置":
            tap(node)
            return "Opened permission settings from the app"
        if owner == "com.android.settings" and node.get("checkable") == "true":
            if node.get("enabled") != "true":
                continue
            if node.get("checked") == "true":
                adb("shell", "input", "keyevent", "KEYCODE_BACK")
                return "Returned after verifying the allowed source"
            tap(node)
            return "Allowed this app as an installation source"
        if (owner.endswith(".packageinstaller") or owner.endswith(".permissioncontroller")) and label in {
                "install", "update", "continue"} and node.get("enabled") == "true":
            tap(node)
            return "Confirmed the Android system installer"
    return None


if __name__ == "__main__":
    assert adb("shell", "getprop", "ro.kernel.qemu").strip() == "1"
    apk, = Path(sys.argv[1]).glob("*.apk")
    target = sys.argv[2]
    adb("install", str(apk), timeout=90)
    before = version()
    assert tuple(map(int, before.split("."))) < tuple(map(int, target.split("."))), (before, target)
    print(f"Starting the installed {before} to update to {target}", flush=True)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    deadline = time.monotonic() + 210
    last_screen = []
    while time.monotonic() < deadline:
        if version() == target:
            print("System package manager reports the released version " + target, flush=True)
            break
        try:
            nodes = visible_nodes()
            last_screen = [n.get("text") for n in nodes if n.get("text")]
            action = confirm_update(nodes)
            if action:
                print(action, flush=True)
        except (subprocess.SubprocessError, ET.ParseError):
            pass  # The window also changes while the original process is being replaced.
        time.sleep(2)
    else:
        print(last_screen)
        print(adb("logcat", "-d", "-t", "600", "PackageInstaller:I", "AndroidRuntime:E", "*:S"))
        raise RuntimeError("The installed app did not update")
