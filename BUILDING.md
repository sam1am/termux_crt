# Building Android APKs from this device

This is a record of how to build Android APKs from inside the
`proot-distro debian` install running on top of Termux on this aarch64
phone. None of the official Android tooling ships aarch64 binaries, so
the setup is more involved than on an x86_64 laptop.

The whole thing runs **without root on the phone** and **without root
inside the PRoot** — every install goes into `$HOME` or into a per-user
SDK tree.

---

## 1. Host context

- CPU: aarch64 (ARM64)
- Termux app (from F-Droid / GitHub release) is installed and you've
  run `termux-setup-storage` once so `~/storage/downloads` exists.
- Inside Termux you've installed `proot-distro` and a Debian rootfs:
  `proot-distro install debian`, then `proot-distro login debian`.
- Inside Debian you have JDK 21 from apt (`openjdk-21-jdk-headless`)
  and curl, unzip, git.

All paths below assume you are logged into the Debian PRoot as the
unprivileged user `sam` with `HOME=/home/sam`.

---

## 2. SDK layout

The SDK lives entirely under `$HOME/android/sdk/` so nothing needs
root. The shape ends up looking like:

```
~/android/sdk/
  cmdline-tools/latest/        # sdkmanager etc. (host-arch JARs, runs on any JDK)
  platform-tools/              # adb, fastboot (aarch64 builds from lzhiyong)
  build-tools/35.0.2/          # aapt, aapt2, aidl, dx, zipalign (aarch64)
  platforms/android-36/        # android.jar + stubs
  ndk/29.0.14206865/           # clang/lld toolchain (aarch64-hosted)
  licenses/                    # acceptance hash files
```

### 2.1 cmdline-tools

These are just JARs, so the official Google `commandlinetools-linux-*.zip`
works as-is on aarch64. Unzip into `~/android/sdk/cmdline-tools/latest/`.

### 2.2 platform-tools and build-tools — aarch64 builds

The Google-hosted ZIPs in the repository index contain `linux-x86_64`
binaries. On aarch64 they will not execute. Use the **aarch64 repacks**
from <https://github.com/lzhiyong/android-sdk-tools/releases> and unpack
them into the SDK tree manually.

After dropping `build-tools/35.0.2/` in place, AGP will refuse to use
it because there is no `package.xml`. Write a minimal one yourself:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02"
                xmlns:ns3="http://schemas.android.com/repository/android/generic/02">
  <localPackage path="build-tools;35.0.2" obsolete="false">
    <type-details xsi:type="ns3:genericDetailsType"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"/>
    <revision><major>35</major><minor>0</minor><micro>2</micro></revision>
    <display-name>Android SDK Build-Tools 35.0.2</display-name>
    <uses-license ref="android-sdk-license"/>
  </localPackage>
</ns2:repository>
```

Save that as `~/android/sdk/build-tools/35.0.2/package.xml`.

### 2.3 platforms/android-36

Use the official `platform-36_r02.zip` from
`https://dl.google.com/android/repository/`. It is pure Java/XML, no
host binaries, so it works as-is. Unzip into `~/android/sdk/platforms/`
and rename the `android-NN` directory to `android-36`.

### 2.4 NDK r29 — aarch64 build

Google does not publish an aarch64 NDK. Use the static (zig + musl)
repack at <https://github.com/lzhiyong/termux-ndk/releases>:

```
android-ndk-r29-aarch64.7z
sha256: 21ca4237997da6c601eda6de48418609d6d8308b26c631620ae57cf1fa06c4c7
```

Two extraction gotchas:

1. You need `7z`. Debian's `7zip` package is fine, but installing it
   needs root. As a workaround you can `apt-get download 7zip`, then
   `dpkg-deb -x 7zip_*.deb extracted/` and copy `extracted/usr/lib/7zip/7z`
   plus `7z.so` into `~/.local/lib/7zip/`, edit the shim in
   `~/.local/bin/7z` to point there.
2. The archive uses a multi-hop symlink (`clang++ -> clang -> clang-21`)
   that 7z 25+ refuses to extract for safety. Extract with `-snl`, then
   manually recreate the dropped symlinks:

   ```bash
   NDK=~/android/sdk/ndk/29.0.14206865
   BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
   for f in $BIN/*clang++; do
     if [ -f "$f" ] && [ ! -s "$f" ]; then
       base=$(basename "$f")
       if [ "$base" = "clang++" ]; then
         rm "$f" && ln -s clang-21 "$f"
       else
         rm "$f" && ln -s "${base%++}" "$f"
       fi
     fi
   done
   ```

The directory is **named `linux-x86_64` even though the binaries are
aarch64** — that is intentional. AGP and `ndk-build` hard-code
`linux-x86_64` as the host tag on Linux; the repack relies on that and
puts a `linux-aarch64-> linux-x86_64` symlink alongside for tools that
do proper host detection. Don't delete `linux-x86_64/`.

After extraction:

```bash
~/android/sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --version
# Android (...) clang version 21.0.0
# Target: aarch64-unknown-linux-musl
```

### 2.5 License acceptance

AGP refuses to build until the SDK licenses are explicitly accepted.
Write the well-known hash files directly — `sdkmanager --licenses` is
not needed:

```bash
mkdir -p ~/android/sdk/licenses

cat > ~/android/sdk/licenses/android-sdk-license <<'EOF'

8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF

cat > ~/android/sdk/licenses/android-sdk-preview-license <<'EOF'

84831b9409646a918e30573bab4c9c91346d8abd
504667f4c0de7af1a06de9f4b1727b84351f2910
EOF

cat > ~/android/sdk/licenses/android-sdk-arm-dbt-license <<'EOF'

859f317696f67ef3d7f30a50a5560e7834b43903
EOF
```

The leading blank line in each file is required.

---

## 3. Per-project configuration

### local.properties

In the project root:

```properties
sdk.dir=/home/sam/android/sdk
ndk.dir=/home/sam/android/sdk/ndk/29.0.14206865
```

This file is `.gitignore`d and intentionally only present on this
device.

### aapt2 override (build-time only)

AGP ships its own `aapt2` inside the AGP jar — and that copy is
`linux-x86_64`. It will fail to spawn on aarch64 with a "Daemon startup
failed" error. Point AGP at the aarch64 aapt2 from build-tools instead.

Do **not** add this to the committed `gradle.properties` (the path is
machine-specific). Put it in your user-level Gradle config so every
project here picks it up:

```bash
mkdir -p ~/.gradle
cat >> ~/.gradle/gradle.properties <<'EOF'
android.aapt2FromMavenOverride=/home/sam/android/sdk/build-tools/35.0.2/aapt2
EOF
```

---

## 4. Building

From the project root:

```bash
export ANDROID_HOME=$HOME/android/sdk
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export PATH=$ANDROID_HOME/build-tools/35.0.2:$JAVA_HOME/bin:$PATH

./gradlew :app:assembleDebug
```

Expected non-fatal warnings to ignore:

- `WARNING: [CXX5106] NDK was located by using ndk.dir property` —
  cosmetic; AGP wants you to migrate to `android.ndkVersion` in each
  module's Gradle block. The build still works.
- `[CXX1104] NDK ... 29.0.14206865 disagrees with android.ndkVersion
  [27.0.12077973]` — some modules hard-code a different ndkVersion;
  affects nothing in this repo's output.
- `Unable to strip the following libraries` — the NDK's `strip` is the
  x86_64 binary; libs are packaged unstripped. APK is slightly larger
  but functionally identical.

Output:

```
app/build/outputs/apk/debug/termux-crt_debug.apk   (~15 MB)
```

---

## 5. Getting the APK off the PRoot

PRoot maps your Debian user to a regular Linux UID. That UID is **not**
in Android's `media_rw` / external storage groups, so writes to
`/sdcard/Download` are denied even though Termux itself has the
permission.

Workaround: copy into Termux's home (which is writable from PRoot),
then move from a Termux shell.

From inside the PRoot:

```bash
cp app/build/outputs/apk/debug/termux-crt_debug.apk \
   /data/data/com.termux/files/home/
```

From a plain Termux shell (`exit` out of PRoot first):

```bash
mv ~/termux-crt_debug.apk ~/storage/downloads/
```

Or skip the move and install via intent:

```bash
am start -a android.intent.action.VIEW \
  -d "file:///data/data/com.termux/files/home/termux-crt_debug.apk" \
  -t application/vnd.android.package-archive
```

Add `allow-external-apps=true` to `~/.termux/termux.properties` once if
the `am` form refuses.

---

## 6. Things that will trip you up

- **Disk space.** `/tmp` and `/` are the same tmpfs and share the
  phone's userdata partition. Extracting the NDK plus the zip wants
  ~3 GB free; if you run low, `unzip` silently truncates and produces a
  toolchain that's missing `bin/`. Delete download zips immediately
  after verifying their SHA1.
- **`Permission denied` running clang.** That is almost always a 0-byte
  `clang++` symlink that 7z refused to extract — see section 2.4.
- **AGP picks `linux-x86_64` even though I'm on aarch64.** Yes, that is
  correct. The aarch64 NDK repack is staged under that name on purpose.
- **`Failed to install build-tools;35.0.0`.** AGP only sees an SDK
  package if there's a `package.xml` next to its files (section 2.2).
  Without it, AGP tries to download the missing version and fails on
  the network + license check.
- **`AAPT2 ... Daemon startup failed`.** Bundled aapt2 is x86_64. Set
  `android.aapt2FromMavenOverride` (section 3).

---

## 7. CI

The repo has a GitHub Actions workflow at
`.github/workflows/build.yml` that runs on every push. CI runs on a
plain x86_64 Ubuntu runner, so none of the above workarounds apply
there — `android-actions/setup-android@v3` installs Google's official
toolchains and `./gradlew :app:assembleDebug` works out of the box.
