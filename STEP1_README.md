# Meshtastic для Android 5.1 — Шаг 1: BLE стек

## Что создано

```
app/
├── build.gradle                    ← конфигурация проекта
├── src/main/
│   ├── AndroidManifest.xml         ← все разрешения BLE + Service
│   └── java/com/mesh51/app/
│       ├── MeshApplication.kt      ← точка входа
│       └── ble/
│           ├── BleConstants.kt     ← UUID сервисов Meshtastic
│           ├── BleManager.kt       ← сканирование + GATT соединение
│           ├── ConnectionState.kt  ← состояния соединения (sealed class)
│           ├── MeshService.kt      ← Foreground Service
│           └── BootReceiver.kt     ← автозапуск после перезагрузки
```

## Как собрать

### 1. Android Studio

1. Скачать Android Studio (минимум Hedgehog 2023.1.1)
2. Открыть папку `meshtastic51/` как проект
3. Дождаться gradle sync
4. **Нужно создать placeholder иконку** — см. ниже
5. `Run → Run 'app'` или `Build → Build APK`

### 2. Placeholder иконки (без них не собирается)

Создать файл `app/src/main/res/drawable/ic_mesh_notification.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF"
        android:pathData="M12,2L12,22M2,12L22,12M6,6L18,18M18,6L6,18"/>
</vector>
```

И стандартные launcher иконки в `mipmap-*` папках (можно сгенерировать в Android Studio:
правая кнопка на `res` → `New → Image Asset`).

### 3. Тема приложения

В `res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.Mesh51" parent="Theme.MaterialComponents.DayNight.DarkActionBar"/>
</resources>
```

## Что делает BleManager

1. `startScan()` — ищет BLE устройства с UUID сервиса Meshtastic
2. `connect(device)` — подключается через GATT
3. После подключения: negotiates MTU → discovers services → enables FROMNUM notify
4. Входящие пакеты: FROMNUM notify → читаем FROMRADIO в цикле → emit в `incomingPackets` Flow
5. `sendPacket(bytes)` — пишет в TORADIO (с фрагментацией если нужно)
6. При обрыве (status 133): экспоненциальный retry до MAX_RECONNECT_ATTEMPTS

## Особенности Android 5.1 / Meizu Flyme

- `connectGatt()` только из UI потока
- `discoverServices()` с задержкой 600ms после подключения
- Между GATT операциями задержка 200ms
- GATT операции строго последовательные через Channel
- Foreground Service защищает от убийства Flyme

## Следующий шаг (Шаг 2)

- Добавить `.proto` файлы Meshtastic
- Настроить protobuf codegen
- Написать MeshPacketParser (ToRadio / FromRadio)
- Написать MeshRepository как прослойку между BleManager и UI
