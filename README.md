# Titan Anime

Titan Anime is an Android application built with Jetpack Compose that transforms real-world video clips into stylized anime animations using a remote Google Colab GPU backend.

## Features

- **Google Colab GPU Backend**: Connect to your free Google Colab T4 GPU notebook via a Cloudflare tunnel (`.trycloudflare.com`).
- **White-box Cartoonizer & AnimeGAN**: High-performance frame-by-frame neural anime video stylization.
- **Client-Side Video Compression**: Optional on-device video transcoding (High 720p, Medium 540p, Fast 360p) powered by Media3 Transformer to speed up upload and GPU inference.
- **Embedded Player**: Play stylized anime clips directly in the app using Media3 ExoPlayer with loop and full controller support.
- **Gallery Export**: Save stylized videos to your device's public photo and video gallery (`Movies/TitanAnime`).
- **Offline Library**: Locally cache and review generated clips using an offline Room Database.
- **Interactive Backend Guide**: In-app 3-step setup dialog with copyable Colab code snippets and an external launch shortcut.

## Architecture & Tech Stack

- **UI Framework**: Jetpack Compose (Material 3 Dark Theme)
- **Architecture**: MVVM with Kotlin Coroutines and StateFlow
- **Media Engine**: AndroidX Media3 (ExoPlayer & Transformer)
- **Local Persistence**: Room Database
- **Networking**: OkHttp 4 with multipart streaming and progress monitoring
- **Minimum SDK**: Android 7.0 (API 24) | Target SDK: Android 15 (API 36)

## Getting Started

1. Open the in-app **Help** dialog (`?` icon in the top app bar).
2. Follow the 3-step instructions to launch a Google Colab notebook with a free T4 GPU.
3. Run the 3 setup cells to start the FastAPI server and open the Cloudflare tunnel.
4. Paste your public `.trycloudflare.com` URL into the app's server configuration card.
5. Select a video clip, choose your desired compression level, and tap **Stylize Video to Anime**.
