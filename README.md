# Snake Pro - Nokia Style

A professional Nokia-style Snake game optimized for **Android 4.4 (KitKat)** and above. This project focuses on high performance, classic gameplay, and modern features while maintaining backward compatibility.

## 🚀 Features

- **Classic Gameplay:** Smooth snake movement with swipe and D-pad support.
- **Multiple Maps:** Choose from various map types (Classic, Box, Tunnel, Mill, Rails, Apartment).
- **Speed Levels:** 8 adjustable speed levels to challenge your skills.
- **Golden Bonus:** A special golden apple that appears with a 20% chance.
    - Worth 5 points.
    - 3.5-second countdown timer.
    - High-quality visual effects (pulse, particles, and digital timer).
- **Bilingual Support:** Full support for Hebrew and English.
- **High Score System:** Saves your best performance locally.
- **Save & Resume:** Automatically saves your game state so you can continue later.
- **Vibration Feedback:** Haptic feedback for eating and game over (toggleable).

## 🛠 Technical Highlights

- **SurfaceView:** Uses a high-performance drawing loop for smooth animations.
- **API 19 Compatibility:** Specifically optimized for Android 4.4 (KitKat) using MultiDex and custom graphics implementations.
- **Custom Graphics:** Implements `drawRoundRectCompat` to support rounded rectangles on devices older than Android 5.0 (API 21).
- **Immersive Mode:** Uses KitKat's `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` for a true full-screen experience.

## 📱 Screenshots

*(Coming soon)*

## 🛠 Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/yt2178/Snake.git
   ```
2. Open in Android Studio.
3. Build and run on an emulator or a physical device (API 19+).

---
Developed by **The Creator YT** as a tribute to the classic mobile gaming era.
