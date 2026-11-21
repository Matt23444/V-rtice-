using System;
using System.Globalization;
using System.IO;

namespace Vortice
{
    /// <summary>Ajustes guardados en %AppData%\Vortice\config.ini</summary>
    internal class Config
    {
        public int ShortSide = 720;   // 0 = resolución nativa
        public int Fps = 30;
        public int VideoMbps = 3;
        public int AudioKbps = 96;
        public bool SysAudio = true;
        public bool Mic = false;
        public bool Bubble = true;
        public string OutDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "Vortice");

        private static string FilePath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                    "Vortice", "config.ini");
            }
        }

        public static Config Load()
        {
            var c = new Config();
            try
            {
                if (!File.Exists(FilePath)) return c;
                foreach (var line in File.ReadAllLines(FilePath))
                {
                    int i = line.IndexOf('=');
                    if (i <= 0) continue;
                    string k = line.Substring(0, i).Trim();
                    string v = line.Substring(i + 1).Trim();
                    int n;
                    switch (k)
                    {
                        case "ShortSide": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) c.ShortSide = n; break;
                        case "Fps": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) c.Fps = n; break;
                        case "VideoMbps": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) c.VideoMbps = n; break;
                        case "AudioKbps": if (int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out n)) c.AudioKbps = n; break;
                        case "SysAudio": c.SysAudio = v == "1"; break;
                        case "Mic": c.Mic = v == "1"; break;
                        case "Bubble": c.Bubble = v == "1"; break;
                        case "OutDir": if (v.Length > 0) c.OutDir = v; break;
                    }
                }
            }
            catch (Exception) { }
            return c;
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
                File.WriteAllLines(FilePath, new[]
                {
                    "ShortSide=" + ShortSide,
                    "Fps=" + Fps,
                    "VideoMbps=" + VideoMbps,
                    "AudioKbps=" + AudioKbps,
                    "SysAudio=" + (SysAudio ? "1" : "0"),
                    "Mic=" + (Mic ? "1" : "0"),
                    "Bubble=" + (Bubble ? "1" : "0"),
                    "OutDir=" + OutDir,
                });
            }
            catch (Exception) { }
        }
    }
}
