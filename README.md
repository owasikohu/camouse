# Camouse

Android 9 (API 28) 以降の端末を、背面カメラで机面の移動を読む Bluetooth HID マウスにする最小 PoC です。Kotlin、CameraX、Android 標準の `BluetoothHidDevice` を使用し、PC 用アプリや OpenCV は不要です。

## ビルド

Android Studio でこのフォルダを開き、Android SDK Platform 35 と Build Tools 35 をインストールして `app` をビルドします。JDK 17 を使用します。CLI では Gradle 8.9 を使い、`gradle assembleDebug` を実行します。APK は `app/build/outputs/apk/debug/app-debug.apk` です。

## 最初の HID 確認

1. 端末の Bluetooth を設定で ON にし、アプリを起動します。Camera と「近くのデバイス」の権限を許可します。Android 12 以降では `BLUETOOTH_CONNECT` と `BLUETOOTH_ADVERTISE` を実行時に要求します。
2. 画面に「HID: 登録済み」と出ることを確認します。失敗する場合、その端末の Bluetooth スタックが HID Device profile を提供しない可能性があります。
3. 「PCから見えるようにする」を押し、PC の Bluetooth 設定から `Camouse` をペアリングします。必要なら「ペア済みPCへ接続」で接続先を選びます。
4. 「接続先」に PC 名が表示されたら「HIDテスト: 右へ10」を押します。PC のカーソルが右に 10 カウント動けば、カメラなしで HID 経路を確認できます。

この実装は **Bluetooth Classic HID Device** です。BLE HID over GATT ではありません。PC は通常の Bluetooth HID マウスとして扱いますが、PC の Bluetooth アダプターと OS が Classic HID を受け付ける必要があります。`BluetoothHidDevice` は API 28 から使用できます。`registerApp` は端末や OS の HID Device 対応状況に依存し、登録成功と接続はコールバックで確認します。アプリは画面を表示したまま使用してください。

## カメラ追跡

「Start」で背面カメラの `ImageAnalysis` を起動します。320×240 を希望解像度として要求しますが、端末が選ぶ実解像度やフレームレートは異なる場合があります。中央 96×96 ピクセルの輝度画像を取り、64×64 の領域を前フレームと ±12 ピクセルの範囲で block matching します。フレーム全体の明るさの変化を補正し、カメラの手ぶれ補正を無効にします。机に模様が乏しい、動きが速すぎる、一致が曖昧、という場合は confidence を下げて送信せず、画面に理由を表示します。低速のノイズは 1.5 カウントの dead zone で抑えます。

「Torch ON/OFF」で照明を切り替えます。フラッシュ非搭載端末では点灯しません。Sensitivity スライダーは 0.5～8.5 倍です。向きが逆なら [MainActivity.kt](app/src/main/java/dev/camouse/MainActivity.kt) の `INVERT_X` または `INVERT_Y` を変更します。カメラ像と端末移動は逆方向なので、標準設定で像の動きを反転して送信します。

Focus スライダーは 0 でオートフォーカス、値を上げるほど近距離の手動フォーカスです。まず机面からレンズまで 10～15cm ほど離し、模様のあるマウスパッド上で `Focus` と `Torch` を調整してください。机面がレンズの最短撮影距離より近いと、スライダーを最大にしても追跡できません。特に 1cm 未満での密着使用は通常のスマートフォン背面カメラでは難しく、外付けマクロレンズや適切な高さのスペーサーが必要です。クリック、ホイール、回転補正、バックグラウンド動作は未実装です。
