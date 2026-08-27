# 東京東筑会 会員QR読取

宛名ラベルの QR コードを読み取り、宛先不明で返送された封入物の記録を効率化する Android 業務アプリです。

読み取った QR コードから会員 ID と氏名を取得して一覧化し、未達者管理 API およびスプレッドシート連携用 API へ送信します。

## 主な機能

- CameraX と ML Kit による QR コード読み取り
- 暗号化済み QR コードの AES-GCM 復号
- 会員 ID・氏名の一覧表示と重複除外
- 読み取り済み ID の未達者管理 API への登録
- 暗号化済み ID のスプレッドシート連携用 API への送信
- 未達者 ID 一覧の取得と表示

## 動作要件

- Android 7.0（API 24）以上
- カメラを使用できる Android 端末
- JDK 17
- Android Studio または Android SDK

QR コードの読み取り確認には実機を推奨します。初回起動時にカメラ権限を許可してください。

## 開発環境のセットアップ

1. リポジトリを Android Studio で開き、Gradle Sync を実行します。
2. プロジェクト直下に `local.properties` を作成します。Android SDK のパスと、以下の設定値を記載してください。
3. 実機またはエミュレータで `app` を実行します。

`local.properties` の例です。値は環境ごとのものを設定し、リポジトリにはコミットしないでください。

```properties
sdk.dir=/path/to/Android/sdk

# アプリのバージョン。X.Y.Z 形式で、各桁は 1 桁の数字です。
version=1.0.0

# QR コード復号用の 32 バイト（64 桁の16進数）キー
qr.decrypt.key.hex=<64-character-hex-key>

# 未達者管理 API
newsletter.api.key=<api-key>
newsletter.api.endpoint=https://example.com/api

# スプレッドシート連携用 API
# キー名の "spreadshet" は現行実装に合わせた表記です。
newsletter.spreadshet.api.key=<api-key>
newsletter.spreadshet.api.endpoint=https://example.com/api
```

QR の復号・API 連携を使わない画面確認だけであれば、`version` 以外の値を空にしてデバッグビルドできます。ただし、復号や送信・一覧取得の操作は利用できません。

## ビルドと実行

Android Studio から `app` を実行するか、コマンドラインで以下を実行します。

```bash
./gradlew assembleDebug
```

生成される APK は `app/build/outputs/apk/debug/` に出力されます。

リリースビルドには、上記に加えて署名情報が必要です。環境変数または `local.properties` に次のいずれかを設定してください。

```properties
android.keystore.path=/path/to/release-keystore.jks
# または android.keystore.base64=<base64-encoded-keystore>
android.keystore.password=<store-password>
android.keystore.alias=<key-alias>
android.keystore.alias.password=<key-password>
```

環境変数では、`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` も使用できます。

```bash
./gradlew assembleRelease
```

## 配布とインストール

APK は Firebase App Distribution で配布します。利用者向けのインストール手順は [documents/install.md](documents/install.md) を参照してください。

アプリの基本操作は [documents/usage.md](documents/usage.md) を参照してください。

## 関連資料

- [アプリ概要](documents/address_label_qr_overview.md)
- [QR コード暗号化の整理](documents/qr_encryption_report.md)
- [Android アプリに関する調査メモ](documents/android_app_report.md)

## 技術構成

- Kotlin / Jetpack Compose
- CameraX
- Google ML Kit Barcode Scanning
- Android Gradle Plugin 8.4.2
- Kotlin 1.9.24
