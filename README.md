<h1><img src="docs/icon.png" width="42" height="42" alt="OHO+ Monet icon"> OHO+ Monet</h1>

Set Monet colors (or any custom color) for One Hand Operation+ handles. Supports **Arrow 3** and **Curved line** styles.

**Shizuku** is required for this app to work. This app has no internet permission and all data are stored locally on device.

## Features

- **Direct method** (default) — sets colors straight in OHO+ settings. Fast and exact.
- **Picker method** — emulates taps on the OHO+ color picker. Slower and inaccurate, should only be used if Direct has issues on your device.
- **Custom colors with alpha** — use `#RRGGBB` or `#AARRGGBB`.

## Screenshots

| App | Arrow 3 | Curved line |
| :-: | :-----: | :---------: |
| <img src="docs/screenshots/app.jpg" width="260" alt="OHO+ Monet app interface"> | <img src="docs/screenshots/arrow3.jpg" width="260" alt="Arrow 3 handle with Monet color"> | <img src="docs/screenshots/curved-line.jpg" width="260" alt="Curved line handle with Monet color"> |

## Installation

<a href="https://github.com/FuLygon/oho-monet/releases"><img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="80"></a>
<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium%3A%2F%2Fadd%2Fhttps%3A%2F%2Fgithub.com%2FFuLygon%2Foho-monet"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a>

## How it works

**Direct method** reads the current OHO+ settings, changes colors, and writes them back. This method need `WRITE_SECURE_SETTINGS` permission and will be granted via **Shizuku**.

**Picker method** opens OHO+ and emulates taps on the color picker. Run the in-app **Calibration** at least once is recommended when using this method. Rely on **Shizuku** to emulates screen taps.

Screen taps and calibration for **Picker method** was tested only on **Samsung Galaxy A55**. Other devices may work, but I can't guarantee it.

Any OHO+ updates could potentially broke this app. Updates for compatibility with future OHO+ updates is not guaranteed but I'll try to update this app if I can.