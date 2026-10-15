# KToast

[![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/ktoast.svg)](https://central.sonatype.com/artifact/io.github.logan0817/ktoast)
[![Android](https://img.shields.io/badge/Android-5.0%2B-green.svg)](https://developer.android.com/about/versions/android-5.0)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[简体中文](README.md) · [Documentation](docs/README.md) · [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md)

KToast is an Android toast library for Kotlin and Java. It provides custom foreground styling, a shared sequential queue, duplicate filtering, cancelable requests, and scopes tied to a specific page.

The core supports Android 5.0+ without Compose, Material, or coroutines. Global messages can fall back to a system Toast in the background. Page-scoped messages are canceled when their host stops. For messages that require an action, evaluate Snackbar; for important background information, use notifications.

> The current published version is **1.1.0**. **1.2.0-SNAPSHOT** is implemented and is **not published on Maven Central**; verification results are tracked separately for each commit and workflow run. Runtime policies and LifecycleOwner scopes below require that candidate. See the [1.2.0 verification record](docs/releases/1.2.0.md) (Chinese) for results from each run, coverage and limits.

## Installation

Add Maven Central to your repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Add the published dependency to your app module:

```kotlin
dependencies {
    implementation("io.github.logan0817:ktoast:1.1.0")
}
```

Groovy:

```groovy
implementation 'io.github.logan0817:ktoast:1.1.0'
```

AndroidX App Startup initializes the library automatically. No extra permission or overlay permission is required. If you disable KToast's initializer, call `KToast.init(this)` in `Application.onCreate`; see the [manual initialization instructions](docs/PAGE_SCOPE.md#接入与生命周期) (Chinese) for the Manifest configuration.

## Quick start

Kotlin:

```kotlin
import com.logan.ktoast.toast

"Saved".toast()
```

Java:

```java
import android.widget.Toast;
import com.logan.ktoast.KToast;

KToast.show("Saved", Toast.LENGTH_SHORT);
```

Per-call styling:

```kotlin
import android.graphics.Color
import com.logan.ktoast.toast

"Upload complete".toast {
    backgroundColor = Color.parseColor("#2E7D32")
    textColor = Color.WHITE
    animationDuration = 150L
}
```

The default mode is `REPLACE`. See [core usage](docs/USAGE_EN.md) for styling, queueing, debouncing, duplicate filtering, group cancellation, delays, callbacks, and custom Views.

## Choose an entry point

| Use case | Entry point | When the page stops | Available in |
| --- | --- | --- | --- |
| Application-wide message | `KToast` / `toast` extensions | Keeps global control and system fallback behavior | 1.1.0 |
| Message owned by an Activity | `KToastScope` | Cancels current requests; the scope can be reused after restart | 1.1.0 |
| Message owned by a Fragment View or another Owner | `KToastLifecycleScope` | Closes the old session; restart accepts only new requests | 1.2.0 candidate |

All entry points share one sequential presentation queue. Activity scope example:

```kotlin
import android.app.Activity
import android.os.Bundle
import com.logan.ktoast.KToastScope

class EditorActivity : Activity() {
    private lateinit var toastScope: KToastScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toastScope = KToastScope.create(this)
    }

    // Call from a click or another event after the page starts.
    fun onSaved() {
        toastScope.show("Saved")
    }
}
```

Create a new scope after Activity recreation. See [Activity scopes](docs/PAGE_SCOPE.md) (Chinese) for window constraints, IME avoidance, accessibility timing, themes, Java examples, and threading rules.

## 1.2.0 candidate features

These APIs require a locally built `1.2.0-SNAPSHOT` and are unavailable in the published `1.1.0` dependency above. The [contributing guide](CONTRIBUTING.md#构建本地候选与消费者) (Chinese) explains local candidate generation and consumption.

### Optional runtime policy

Configure on the main thread, after initialization and before registering the first request or delayed task:

```kotlin
import com.logan.ktoast.KToast
import com.logan.ktoast.runtime.KToastRuntimePolicy

KToast.setRuntimePolicy(
    KToastRuntimePolicy(
        maxPendingRequests = 32,
        maxQueueWaitMillis = 10_000L,
        maxDedupeEntries = 256
    )
)
```

All limits default to `null`, preserving existing behavior. At capacity, accepted requests are retained and new ones are rejected, including `REPLACE`. Waiting expiry does not interrupt a displayed message. The numbers are examples, not recommended production settings. See [runtime policies](docs/RUNTIME_POLICY.md) (Chinese) for detailed rules and `dropReason`.

### LifecycleOwner scopes

The optional module is `io.github.logan0817:ktoast-lifecycle:1.2.0-SNAPSHOT`. It depends on the same core version and `lifecycle-common:2.5.1`, without adding Fragment, Compose, or coroutines. Using the core alone does not add Lifecycle.

```kotlin
import com.logan.ktoast.lifecycle.KToastLifecycleScope

// Create and retain in Fragment.onViewCreated.
val toastScope = KToastLifecycleScope.create(requireActivity(), viewLifecycleOwner)
// Register an event on this View; click after the page reaches STARTED.
view.setOnClickListener { toastScope.show("Saved") }
```

The old scope closes permanently when its View is destroyed; create another scope for the new View. See [LifecycleOwner scopes](docs/LIFECYCLE_SCOPE.md) (Chinese).

## Compatibility and behavior

- Minimum runtime: API 21. Library bytecode: JVM 1.8. Kotlin metadata: 1.9. These do not guarantee support for every older compiler or AGP.
- Core production dependencies: Kotlin stdlib 1.9.23 and AndroidX Startup 1.1.1. Tested consumer combinations and Android versions are recorded in the [verification record](docs/releases/1.2.0.md) (Chinese).
- Window operations, content factories, and callbacks run on the main thread. Per-call configuration runs on the calling thread. Configure mutable global styles during initialization or centrally on the main thread.
- `onShow` / `SHOWN` means the render call returned. System Toast completion uses a local timer. Neither guarantees that the user saw the message.
- Extra ProGuard rules are usually unnecessary. See [troubleshooting](TROUBLESHOOTING.md) (Chinese) for integration, fallback, lifecycle, and toolchain issues.

## Documentation and sample

- [Documentation index](docs/README.md): user guides, API, maintenance, and design records. Detailed guides are primarily in Chinese; this README and [core examples](docs/USAGE_EN.md) are available in English.
- [Sample Activity](app/src/main/java/com/logan/ktoastapp/MainActivity.kt) and [Lifecycle sample](app/src/main/java/com/logan/ktoastapp/LifecycleDemoActivity.kt): build current samples from source.
- [Contributing](CONTRIBUTING.md), [release guide](docs/RELEASING.md), and [roadmap](docs/ROADMAP.md) (Chinese).

This historical animation demonstrates basic styling, not verification of the current candidate:

<img src="GIF.gif" width="350" alt="KToast basic styling demonstration" />

## License

[MIT License](LICENSE). Copyright © 2026 Logan Gan.
