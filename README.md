# YQT Tracker — App Android (solo móvil)

App Android nativa (Kotlin) que consulta directamente los servidores YQT/SeTracker (sin PC ni Raspberry) para mostrar todos tus relojes en un mapa, con tu ubicación y modo **Perseguir** (ruta e indicaciones).

## Funciones
- Inicio de sesión (región, cuenta, contraseña) y descubrimiento automático de todos los dispositivos al entrar.
- Mapa (OpenStreetMap) con **todos los relojes** y **nombre al lado de cada pin**. Toca un reloj para seleccionarlo.
- Tu ubicación GPS en el mapa y ruta hacia el reloj seleccionado (OSRM).
- Modo **Perseguir**: seguimiento de tu posición, indicaciones paso a paso en pantalla y por voz (TTS), y reruteo si te desvías o si el reloj cambia de posición.
- Actualización periódica de posiciones (cada ~10 s).

## Compilar el APK con GitHub Actions (sin Android Studio)
1. Crea un repositorio en tu cuenta de GitHub (público o privado).
2. Sube **todo el contenido de esta carpeta** (`android-app`) al repositorio, incluyendo la carpeta oculta `.github/workflows/` (contiene `build-apk.yml`).
   - Desde la web de GitHub: *Add file → Upload files* y arrastra los archivos/carpetas. Asegúrate de que `.github/workflows/build-apk.yml` queda en el repo.
3. Ve a la pestaña **Actions** del repositorio. El workflow "Build Android APK" se ejecutará (o lánzalo con *Run workflow*).
4. Cuando termine correctamente, entra en la ejecución y descarga el artefacto **`yqt-tracker-debug-apk`** (contiene el `.apk`).
5. Pasa el APK a tu móvil Android e instálalo (puede que tengas que permitir instalar apps de orígenes desconocidos).

> Compila en los servidores de GitHub (runner Linux) con JDK 17, Gradle y Android SDK. No necesitas instalar nada en tu PC.

## Uso
1. Abre la app e inicia sesión con tu región, cuenta y contraseña (las mismas de la app del reloj).
2. Concede permiso de ubicación para ver tu posición y usar el modo Perseguir.
3. Se mostrarán todos los relojes con su nombre. Toca uno para seleccionarlo; pulsa **Perseguir** para navegación con indicaciones (pulsa de nuevo para detener).

## Notas
- La app se conecta directamente a la API de YQT (`myaqsh.com`), igual que SeTracker. Necesita Internet (y los mapas/rutas usan OpenStreetMap/OSRM).
- Proyecto no oficial: el proveedor puede cambiar la API o el certificado, lo que requeriría actualizar la app.
- Credenciales: se guardan localmente en el dispositivo para no tener que reescribirlas; usa el menú para cerrar sesión y borrarlas.
- El APK generado por el workflow es de **debug** (apto para uso personal/pruebas). Para distribución formal habría que firmarlo como release.

## Estructura principal
- `app/src/main/java/com/yqt/tracker/network/` — cliente/protocolo (mTLS, cifrado, firma, endpoints).
- `app/src/main/java/com/yqt/tracker/ui/` — pantallas (login y mapa/navegación).
- `app/src/main/assets/client.pem` — certificado cliente usado por la API.
- `.github/workflows/build-apk.yml` — compilación automática en GitHub Actions.
