# Timer Bunyi

## Building

Requires Java 17+ and Android SDK with platform 34 and build-tools 34.0.0.

### Setting up Android SDK (no Android Studio)

```bash
# Download command-line tools
mkdir -p /opt/android-sdk && cd /opt/android-sdk
curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o cmdline-tools.zip
unzip -q cmdline-tools.zip
mkdir -p cmdline-tools/latest && mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/

# Set environment
export ANDROID_HOME=/opt/android-sdk

# Accept licenses and install components
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0"

# Create local.properties in project root
echo "sdk.dir=/opt/android-sdk" > local.properties
```

### Building the APK

```bash
export ANDROID_HOME=/opt/android-sdk
gradle assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`
