import getpass
import os
import re
import shutil
import subprocess
import sys
import time

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE = "com.example.first_app"
LAUNCH_COMPONENT = f"{PACKAGE}/{PACKAGE}.MainActivity"
GRADLEW = os.path.join(PROJECT_DIR, "gradlew.bat" if os.name == "nt" else "gradlew")
OUTPUTS_DIR = os.path.join(PROJECT_DIR, "app", "build", "outputs")
DEBUG_APK = os.path.join(OUTPUTS_DIR, "apk", "debug", "app-debug.apk")
RELEASE_APK = os.path.join(OUTPUTS_DIR, "apk", "release", "app-release.apk")
RELEASE_AAB = os.path.join(OUTPUTS_DIR, "bundle", "release", "app-release.aab")
# Installs as a separate app under a package Qualcomm phones whitelist for hidden lenses (e.g. ultra-wide).
ALL_LENSES_PACKAGE = "org.codeaurora.snapcam"
ALL_LENSES_APK = os.path.join(OUTPUTS_DIR, "apk", "allLenses", "app-allLenses.apk")
RELEASE_DIR = os.path.join(PROJECT_DIR, "release")
APP_GRADLE = os.path.join(PROJECT_DIR, "app", "build.gradle.kts")
LINT_REPORT = os.path.join(PROJECT_DIR, "app", "build", "reports", "lint-results-release.html")
KEYSTORE_PROPS = os.path.join(PROJECT_DIR, "keystore.properties")
DEFAULT_KEYSTORE = "dualcam-release.keystore"
DEFAULT_KEY_ALIAS = "dualcam"
MIN_JAVA_VERSION = 17
EXE = ".exe" if os.name == "nt" else ""
EMULATOR_BOOT_TIMEOUT = 300


def find_sdk_dir():
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return os.environ[var]

    local_props = os.path.join(PROJECT_DIR, "local.properties")
    if os.path.exists(local_props):
        with open(local_props, encoding="utf-8") as f:
            for line in f:
                if line.startswith("sdk.dir="):
                    return line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    return None


def find_sdk_tool(subdir, name):
    sdk_dir = find_sdk_dir()
    if sdk_dir:
        path = os.path.join(sdk_dir, subdir, name + EXE)
        if os.path.exists(path):
            return path
    return shutil.which(name)


def find_build_tool(name):
    """Finds a tool such as apksigner in the newest installed SDK build-tools."""
    sdk_dir = find_sdk_dir()
    build_tools = os.path.join(sdk_dir, "build-tools") if sdk_dir else ""
    if not os.path.isdir(build_tools):
        return None

    def version_key(version):
        return [int(part) if part.isdigit() else 0 for part in re.split(r"[.\-]", version)]

    ext = ".bat" if os.name == "nt" else ""
    for version in sorted(os.listdir(build_tools), key=version_key, reverse=True):
        path = os.path.join(build_tools, version, name + ext)
        if os.path.exists(path):
            return path
    return None


def find_java_tool(name):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.path.join(os.environ["JAVA_HOME"], "bin", name + EXE))
    if shutil.which(name):
        candidates.append(shutil.which(name))
    if os.name == "nt":
        program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
        candidates.append(os.path.join(program_files, "Android", "Android Studio", "jbr", "bin", name + EXE))
    return next((path for path in candidates if os.path.exists(path)), None)


ADB = find_sdk_tool("platform-tools", "adb")
EMULATOR = find_sdk_tool("emulator", "emulator")


def run_command(command):
    print(f"\n> {' '.join(command)}")
    return subprocess.run(command, cwd=PROJECT_DIR).returncode == 0


def adb_output(*args):
    result = subprocess.run([ADB, *args], capture_output=True, text=True)
    return result.stdout.strip()


def list_devices():
    """Returns (serial, state) pairs, e.g. ("emulator-5554", "device")."""
    devices = []
    for line in adb_output("devices").splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            devices.append((parts[0], parts[1]))
    return devices


def is_emulator(serial):
    return serial.startswith("emulator-")


def device_name(serial):
    if is_emulator(serial):
        avd = adb_output("-s", serial, "emu", "avd", "name").splitlines()
        return f"{avd[0]} (emulator)" if avd else "Emulator"
    model = adb_output("-s", serial, "shell", "getprop", "ro.product.model")
    return model or serial


def choose(items, label):
    if len(items) == 1:
        return items[0]

    print(f"\nSelect {label}:")
    for i, (_, name) in enumerate(items, start=1):
        print(f"{i}. {name}")
    choice = input("Choose a number: ").strip()
    if choice.isdigit() and 1 <= int(choice) <= len(items):
        return items[int(choice) - 1]
    print("Invalid selection.")
    return None


def require_adb():
    if ADB is None:
        print("adb was not found. Install Android SDK platform-tools from Android Studio's SDK Manager.")
        return False
    return True


def build_debug_apk():
    if not run_command([GRADLEW, "assembleDebug"]):
        print("Build failed.")
        return False
    print(f"Debug APK: {DEBUG_APK}")
    return True


def build_all_lenses_apk():
    if not run_command([GRADLEW, "assembleAllLenses"]):
        print("Build failed.")
        return False
    print(f"All-lenses APK: {ALL_LENSES_APK}")
    return True


def install_and_launch(serial, all_lenses=False):
    if not (build_all_lenses_apk() if all_lenses else build_debug_apk()):
        return
    apk = ALL_LENSES_APK if all_lenses else DEBUG_APK
    component = f"{ALL_LENSES_PACKAGE}/{PACKAGE}.MainActivity" if all_lenses else LAUNCH_COMPONENT
    if not run_command([ADB, "-s", serial, "install", "-r", apk]):
        print("Install failed.")
        return
    if run_command([ADB, "-s", serial, "shell", "am", "start", "-n", component]):
        print("App launched.")
    else:
        print("Failed to launch the app.")


def wait_for_boot(serial):
    print(f"Waiting for {serial} to finish booting...")
    deadline = time.time() + EMULATOR_BOOT_TIMEOUT
    while time.time() < deadline:
        if adb_output("-s", serial, "shell", "getprop", "sys.boot_completed") == "1":
            print("Emulator is ready.")
            return True
        time.sleep(3)
    print("Timed out waiting for the emulator to boot.")
    return False


def start_emulator():
    if EMULATOR is None:
        print("Android emulator was not found. Install it from Android Studio's SDK Manager.")
        return None

    avds = subprocess.run([EMULATOR, "-list-avds"], capture_output=True, text=True).stdout.split()
    if not avds:
        print("No emulators (AVDs) found. Create one in Android Studio > Device Manager.")
        return None

    selected = choose([(avd, avd) for avd in avds], "an emulator to start")
    if selected is None:
        return None
    avd = selected[0]

    existing = {serial for serial, _ in list_devices()}
    print(f"Starting emulator '{avd}'...")
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    subprocess.Popen(
        [EMULATOR, "-avd", avd],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        creationflags=flags,
        start_new_session=os.name != "nt",
    )

    deadline = time.time() + EMULATOR_BOOT_TIMEOUT
    while time.time() < deadline:
        new = [s for s, _ in list_devices() if is_emulator(s) and s not in existing]
        if new:
            return new[0] if wait_for_boot(new[0]) else None
        time.sleep(2)
    print("Timed out waiting for the emulator to appear in adb.")
    return None


def run_on_emulator():
    if not require_adb():
        return

    emulators = [s for s, state in list_devices() if is_emulator(s) and state == "device"]
    if emulators:
        selected = choose([(s, device_name(s)) for s in emulators], "an emulator")
        serial = selected[0] if selected else None
    else:
        print("No running emulator found.")
        serial = start_emulator()

    if serial:
        install_and_launch(serial)


def run_on_physical_device(all_lenses=False):
    if not require_adb():
        return

    devices = [(s, state) for s, state in list_devices() if not is_emulator(s)]
    unauthorized = [s for s, state in devices if state == "unauthorized"]
    ready = [s for s, state in devices if state == "device"]

    if unauthorized:
        print("Some devices are unauthorized. Unlock the phone and accept the 'Allow USB debugging' prompt.")
    if not ready:
        print("No connected mobile device found.")
        print("Enable Developer options > USB debugging on the phone and connect it with a USB cable.")
        return

    selected = choose([(s, device_name(s)) for s in ready], "a device")
    if selected:
        install_and_launch(selected[0], all_lenses)


def run_all_lenses_on_physical_device():
    run_on_physical_device(all_lenses=True)


def show_connected_devices():
    if not require_adb():
        return
    devices = list_devices()
    if not devices:
        print("No devices or emulators connected.")
        return
    print("\nConnected devices:")
    for serial, state in devices:
        name = device_name(serial) if state == "device" else state
        print(f"- {serial}: {name}")


def pick_ready_device():
    ready = [s for s, state in list_devices() if state == "device"]
    if not ready:
        print("No devices or emulators connected.")
        return None
    selected = choose([(s, device_name(s)) for s in ready], "a device")
    return selected[0] if selected else None


def show_app_logs():
    if not require_adb():
        return
    serial = pick_ready_device()
    if not serial:
        return

    pid = adb_output("-s", serial, "shell", "pidof", PACKAGE)
    if not pid:
        print("The app is not running on this device. Launch it first.")
        return

    print("Showing app logs. Press Ctrl+C to return to the menu.")
    try:
        subprocess.run([ADB, "-s", serial, "logcat", f"--pid={pid.split()[0]}"])
    except KeyboardInterrupt:
        print("\nStopped logs.")


def uninstall_app():
    if not require_adb():
        return
    serial = pick_ready_device()
    if serial and run_command([ADB, "-s", serial, "uninstall", PACKAGE]):
        print("App uninstalled.")


def app_version():
    """Returns (versionName, versionCode) from app/build.gradle.kts."""
    with open(APP_GRADLE, encoding="utf-8") as f:
        text = f.read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (name.group(1) if name else "unknown", code.group(1) if code else "unknown")


def read_properties(path):
    props = {}
    if not os.path.exists(path):
        return props
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                props[key.strip()] = value.strip().replace("\\\\", "\\")
    return props


def signing_configured():
    props = read_properties(KEYSTORE_PROPS)
    if not all(props.get(key) for key in ("storeFile", "storePassword", "keyAlias", "keyPassword")):
        return False
    return os.path.exists(os.path.join(PROJECT_DIR, props["storeFile"]))


def write_keystore_properties(store_file, alias, store_password, key_password):
    try:
        rel = os.path.relpath(store_file, PROJECT_DIR)
        store_ref = store_file if rel.startswith("..") else rel
    except ValueError:
        store_ref = store_file

    def escape(value):
        return value.replace("\\", "\\\\")

    with open(KEYSTORE_PROPS, "w", encoding="utf-8") as f:
        f.write("# Release signing for app/build.gradle.kts. Never commit this file.\n")
        f.write(f"storeFile={store_ref.replace(os.sep, '/')}\n")
        f.write(f"storePassword={escape(store_password)}\n")
        f.write(f"keyAlias={escape(alias)}\n")
        f.write(f"keyPassword={escape(key_password)}\n")


def ensure_signing():
    """Makes sure keystore.properties points at a usable keystore, creating one if needed."""
    if signing_configured():
        return True

    keytool = find_java_tool("keytool")
    if keytool is None:
        print(f"keytool was not found. Install JDK {MIN_JAVA_VERSION}+ or set JAVA_HOME.")
        return False

    print("\nRelease signing is not set up yet (keystore.properties is missing or incomplete).")
    default_store = os.path.join(PROJECT_DIR, DEFAULT_KEYSTORE)
    if os.path.exists(default_store):
        prompt = f"Path to existing keystore [{DEFAULT_KEYSTORE}]: "
    else:
        prompt = "Path to an existing keystore (leave empty to create a new one): "
    existing = input(prompt).strip().strip('"')
    if not existing and os.path.exists(default_store):
        existing = default_store

    if existing:
        store_file = os.path.abspath(existing)
        if not os.path.exists(store_file):
            print(f"Keystore not found: {store_file}")
            return False
        alias = input(f"Key alias [{DEFAULT_KEY_ALIAS}]: ").strip() or DEFAULT_KEY_ALIAS
        store_password = getpass.getpass("Keystore password: ")
        key_password = getpass.getpass("Key password (leave empty if same as keystore): ") or store_password
        check = subprocess.run(
            [keytool, "-list", "-keystore", store_file, "-storepass", store_password, "-alias", alias],
            capture_output=True,
            text=True,
        )
        if check.returncode != 0:
            print("Could not open the keystore with that alias and password:")
            print((check.stdout or check.stderr).strip())
            return False
    else:
        store_file = default_store
        alias = DEFAULT_KEY_ALIAS
        store_password = getpass.getpass("New keystore password (min 6 characters): ")
        if len(store_password) < 6:
            print("Password must be at least 6 characters.")
            return False
        if getpass.getpass("Confirm password: ") != store_password:
            print("Passwords do not match.")
            return False
        key_password = store_password
        owner = input("Your name or organization (shown in the certificate): ").strip() or "DualCam"
        dname = "CN=" + owner.replace(",", "\\,")

        print(f"\n> keytool -genkeypair -keystore {DEFAULT_KEYSTORE} -alias {alias} ...")
        created = subprocess.run(
            [
                keytool, "-genkeypair", "-keystore", store_file, "-alias", alias,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                "-storepass", store_password, "-keypass", key_password,
                "-dname", dname,
            ],
            cwd=PROJECT_DIR,
        )
        if created.returncode != 0:
            print("Failed to create the keystore.")
            return False
        print(f"Created {DEFAULT_KEYSTORE}.")

    write_keystore_properties(store_file, alias, store_password, key_password)
    print("Saved signing settings to keystore.properties (git-ignored).")
    print("Back up the keystore and its password: every Play Store update must be signed with the same key.")
    return True


def build_release(task, output):
    """Runs a signed Gradle release task and copies the result into release/."""
    if not ensure_signing():
        return None

    if os.path.exists(output):
        os.remove(output)
    if not run_command([GRADLEW, task]):
        print("Build failed.")
        return None
    if not os.path.exists(output):
        print(f"Build finished but the output was not found at {output}")
        return None

    version_name, version_code = app_version()
    extension = os.path.splitext(output)[1]
    os.makedirs(RELEASE_DIR, exist_ok=True)
    target = os.path.join(RELEASE_DIR, f"dualcam-v{version_name}-{version_code}{extension}")
    shutil.copy2(output, target)
    size_mb = os.path.getsize(target) / (1024 * 1024)
    print(f"\nCopied to {os.path.relpath(target, PROJECT_DIR)} ({size_mb:.1f} MB)")
    return target


def build_release_apk():
    apk = build_release("assembleRelease", RELEASE_APK)
    if not apk:
        return

    apksigner = find_build_tool("apksigner")
    if apksigner is None:
        print("apksigner not found in SDK build-tools; skipped signature check.")
        return
    result = subprocess.run([apksigner, "verify", apk], capture_output=True, text=True)
    if result.returncode == 0:
        print("APK signature verified.")
    else:
        print("APK signature check failed:")
        print((result.stdout or result.stderr).strip())


def build_release_aab():
    aab = build_release("bundleRelease", RELEASE_AAB)
    if not aab:
        return

    jarsigner = find_java_tool("jarsigner")
    if jarsigner is None:
        print("jarsigner not found; skipped signature check.")
    else:
        result = subprocess.run([jarsigner, "-verify", aab], capture_output=True, text=True)
        if result.returncode == 0 and "jar verified" in result.stdout:
            print("AAB signature verified.")
        else:
            print("AAB signature check failed:")
            print((result.stdout or result.stderr).strip())

    _, version_code = app_version()
    print(f"Upload this .aab in Play Console. versionCode is {version_code}; Play rejects a versionCode")
    print("that was already uploaded, so bump versionCode in app/build.gradle.kts before the next upload.")


def java_major_version(java):
    result = subprocess.run([java, "-version"], capture_output=True, text=True)
    match = re.search(r'version "(\d+)(?:\.(\d+))?', result.stderr + result.stdout)
    if not match:
        return None
    major = int(match.group(1))
    return int(match.group(2) or 0) if major == 1 else major


def preflight_test():
    """Checks the toolchain, then runs unit tests, lint and a debug build."""
    failures = []

    def report(status, name, detail=""):
        print(f"[{status}] {name}" + (f" - {detail}" if detail else ""))
        if status == "FAIL":
            failures.append(name)

    print("\n--- Environment ---")
    java = find_java_tool("java")
    java_version = java_major_version(java) if java else None
    if java_version is None:
        report("FAIL", "Java", f"not found; install JDK {MIN_JAVA_VERSION}+ or set JAVA_HOME")
    elif java_version < MIN_JAVA_VERSION:
        report("FAIL", "Java", f"version {java_version} at {java}; JDK {MIN_JAVA_VERSION}+ is required")
    else:
        report("PASS", "Java", f"version {java_version} ({java})")

    sdk_dir = find_sdk_dir()
    if sdk_dir and os.path.isdir(sdk_dir):
        report("PASS", "Android SDK", sdk_dir)
    else:
        report("FAIL", "Android SDK", "not found; set ANDROID_HOME or sdk.dir in local.properties")

    report("PASS" if os.path.exists(GRADLEW) else "FAIL", "Gradle wrapper", os.path.basename(GRADLEW))
    if ADB:
        report("PASS", "adb", ADB)
    else:
        report("WARN", "adb", "not found; needed only to run the app on a device")

    if signing_configured():
        report("PASS", "Release signing", "keystore.properties is set up")
    else:
        report("WARN", "Release signing", "not set up yet; options 8/9 will set it up")

    version_name, version_code = app_version()
    report("INFO", "App version", f"versionName {version_name}, versionCode {version_code}")

    if failures:
        print(f"\nPreflight failed: {', '.join(failures)}. Fix the environment before running Gradle checks.")
        return False

    print("\n--- Gradle checks ---")
    checks = [
        ("Unit tests", "testDebugUnitTest"),
        ("Lint (release)", "lintRelease"),
        ("Debug build", "assembleDebug"),
    ]
    for name, task in checks:
        if run_command([GRADLEW, task]):
            report("PASS", name)
        else:
            report("FAIL", name, f"see {LINT_REPORT}" if task == "lintRelease" else "")

    if failures:
        print(f"\nPreflight failed: {', '.join(failures)}")
        return False
    print("\nPreflight passed. Ready to build a release.")
    return True


MENU = [
    ("1", "Run the app in Emulator", run_on_emulator),
    ("2", "Run the app on connected mobile device (USB / adb)", run_on_physical_device),
    ("3", "Show connected devices", show_connected_devices),
    ("4", "Build debug APK only", build_debug_apk),
    ("5", "Show app logs", show_app_logs),
    ("6", "Uninstall the app from a device", uninstall_app),
    ("7", "Preflight test (environment, unit tests, lint, debug build)", preflight_test),
    ("8", "Build .apk app (Release)", build_release_apk),
    ("9", "Build .aab app (Play Store, signed)", build_release_aab),
    ("10", "Run the all-lenses build on a mobile device (unlocks hidden lenses on Qualcomm phones)",
     run_all_lenses_on_physical_device),
    ("0", "Exit", None),
]


def main():
    actions = {key: action for key, _, action in MENU}
    while True:
        print("\n=== DualCam ===")
        for key, label, _ in MENU:
            print(f"{key}. {label}")

        try:
            choice = input("Choose an option: ").strip()
        except (KeyboardInterrupt, EOFError):
            choice = "0"

        if choice == "0":
            print("\nGoodbye!")
            sys.exit(0)
        elif choice in actions:
            try:
                actions[choice]()
            except KeyboardInterrupt:
                print("\nCancelled.")
        else:
            print("Invalid option. Please choose from the menu.")


if __name__ == "__main__":
    main()
