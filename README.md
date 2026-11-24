# Vórtice

Grabador de pantalla con audio interno para Android 10 o superior. Sin librerías externas: usa el codificador por hardware del teléfono, así que el APK pesa muy poco y gasta poca CPU.

## Compilar el APK (sin instalar nada)

1. En GitHub, crea un repositorio nuevo y sube todo el contenido de esta carpeta (incluida la carpeta oculta `.github`).
2. Entra en la pestaña **Actions**. Si el flujo no arrancó solo, abre **Build APK** y pulsa **Run workflow**.
3. Cuando termine (unos 3 a 5 minutos), abre la ejecución y descarga **Vortice-apk** en la sección *Artifacts*.
4. Descomprime el ZIP, pasa el `.apk` al teléfono e instálalo (hay que permitir instalar desde esa fuente).

## Primer uso

1. Abre **Vórtice** y pulsa **Conceder permisos** (audio, notificaciones y «Mostrar sobre otras apps»).
2. Pulsa **Encender overlay**. Aparece una burbuja ● que puedes arrastrar.
3. Toca la burbuja para abrir el panel: **Grabar**, resolución, fotogramas por segundo, calidad de video, audio interno, micrófono y calidad de audio. Con **Micrófono: Sí** se mezcla tu voz con el sonido del teléfono (por defecto está apagado).
4. Al grabar, Android pide confirmar la captura de pantalla (lo hace en cada grabación).
5. Los videos se guardan en `Películas/ScreenRec`.

## Ajustes por defecto

720p, 30 fps, video a 3 Mbps y audio AAC a 96 kbps. Para un equipo modesto, 480p a 24 fps con 1 o 2 Mbps es lo más ligero.

## Limitaciones

- El audio interno no funciona en apps que lo bloquean (algunos juegos y servicios de streaming con protección). En ese caso el video queda sin sonido.
- Si eliges «Burbuja al grabar: visible», la burbuja aparecerá en el video. Con la opción «oculta» se detiene desde la notificación.
- No se puede cambiar la calidad mientras se graba.
- La orientación queda fija con la que tenía la pantalla al empezar a grabar.

---

## Versión para Windows (carpeta `windows/`)

Grabador de pantalla + audio del sistema (y micrófono opcional) para Windows 10/11 de 64 bits. Vive en la bandeja del sistema, usa el codificador H.264 por hardware de Windows (Quick Sync, NVENC o AMD si existen) y no consume nada cuando está inactivo.

1. En **Actions**, abre la última ejecución de **Build Windows** y descarga **Vortice-Windows**.
2. Descomprime. Si la app no abre, instala primero `vc_redist.x64.exe` (incluido) y reinicia.
3. Abre `Vortice.exe`. Atajo global: **Ctrl + Alt + R** para grabar y detener. También hay una burbuja flotante (se puede ocultar en Ajustes).
4. Los videos se guardan en `Videos\Vortice`. Ajustes en `%AppData%\Vortice\config.ini`.

Para gastar poca CPU: 720p, 24 o 30 fps y 2 a 3 Mbps.

---

## Transmitir la pantalla a otro dispositivo (misma red Wi-Fi)

Funciona en ambos sentidos: **Android → PC** y **PC → Android** (también a otro celular o tablet). El que mira **no instala nada**: solo abre un enlace en el navegador.

1. En el dispositivo que transmite, pulsa **Transmitir pantalla** (en Android, desde el panel de la burbuja; en Windows, pestaña **Transmitir** o menú de la bandeja).
2. Aparece un enlace como `http://192.168.1.20:8080/?k=ABC123`. Ábrelo en el navegador del otro dispositivo (mismo Wi-Fi). El enlace incluye una clave para que nadie más pueda mirar.
3. Para oír el sonido, toca **Activar audio** en la página. Si el audio llega con retraso respecto al video, mueve la barra **Sincronía de audio**.

Sin Wi-Fi compartido: activa la zona Wi-Fi del celular y conecta la PC a ella, o usa anclaje USB. Por Bluetooth no es viable (muy lento).

Notas: la imagen va como MJPEG (compatible con cualquier navegador). Sin espectadores no se captura ni se codifica nada. En Windows, la primera vez hay que permitir la app en el firewall para redes privadas. En Android no se puede grabar y transmitir a la vez.

### Grabar lo que se recibe

En la página del visor hay un botón **Grabar aquí**. Graba lo que ves (con el audio, si lo activaste) y al detener descarga un archivo `.webm` en el dispositivo que recibe. Mantén esa pestaña abierta y visible mientras graba. Los archivos largos ocupan memoria: para grabaciones largas es mejor grabar directamente con Vórtice en el dispositivo que transmite.
