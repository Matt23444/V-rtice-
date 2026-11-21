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
