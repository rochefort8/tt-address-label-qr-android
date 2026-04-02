# TT QR Android

Jetpack Compose + Kotlin で作った、QR Code を読み取って内容をダイアログ表示する最小 Android アプリです。

## 機能

- カメラ権限の要求
- CameraX + ML Kit による QR Code 読み取り
- 読み取り結果のポップアップ表示

## 起動方法

1. Android Studio でこのフォルダを開く
2. Gradle Sync を実行する
3. 実機またはエミュレータで `app` を起動する

## 補足

- カメラを使うため、QR Code の読み取り確認は実機がいちばん確実です
- ローカルでは Java / Android SDK が未設定だったため、この環境ではビルド実行までは確認していません
