using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace Vortice
{
    /// <summary>
    /// Transmite la pantalla (y el audio del sistema) por la red local.
    /// Solo captura mientras hay alguien mirando; sin espectadores no gasta CPU.
    /// </summary>
    internal class PcStreamer
    {
        private readonly Config _cfg;
        private readonly SynchronizationContext _ui;
        private StreamServer _server;
        private Thread _capThread;
        private volatile bool _run;
        private WasapiLoopbackCapture _loop;
        private System.Threading.Timer _silenceTimer;
        private long _lastAudioTicks;
        private int _srcRate, _srcChannels, _srcBits;

        public bool Running { get; private set; }
        public string Url { get; private set; }
        public List<string> AllUrls { get; private set; }
        public event Action StateChanged;
        public event Action<string> Info;

        public PcStreamer(Config cfg)
        {
            _cfg = cfg;
            _ui = SynchronizationContext.Current;
            AllUrls = new List<string>();
        }

        private void Raise() { var h = StateChanged; if (h != null) h(); }
        private void Say(string m) { var h = Info; if (h != null) h(m); }
        private void Post(Action a) { if (_ui != null) _ui.Post(_ => a(), null); else a(); }

        public void Toggle() { if (Running) Stop(); else Start(); }

        public void Start()
        {
            if (Running) return;
            try
            {
                var ips = LocalIps();
                if (ips.Count == 0) throw new InvalidOperationException("Conéctate a una red Wi-Fi o a una red local");

                bool audio = _cfg.StreamAudio && StartAudioCapture();
                string key = RandomKey();
                string html = ReadViewer();
                _server = new StreamServer(html, key, audio, audio ? _srcRate : 44100, 2);
                int port = _server.Start();

                AllUrls = ips.Select(ip => "http://" + ip + ":" + port + "/?k=" + key).ToList();
                Url = AllUrls[0];

                _run = true;
                Running = true;
                _capThread = new Thread(CaptureLoop) { IsBackground = true, Name = "pc-capture", Priority = ThreadPriority.BelowNormal };
                _capThread.Start();

                Raise();
                Say("Transmitiendo. Abre en el otro dispositivo: " + Url);
            }
            catch (Exception ex)
            {
                CleanUp();
                Running = false;
                Raise();
                Say("No se pudo transmitir: " + ex.Message);
            }
        }

        public void Stop()
        {
            if (!Running) return;
            CleanUp();
            Running = false;
            Url = null;
            AllUrls = new List<string>();
            Raise();
            Say("Transmisión detenida");
        }

        private void CleanUp()
        {
            _run = false;
            try { if (_capThread != null) _capThread.Join(1500); } catch (Exception) { }
            _capThread = null;
            try { if (_silenceTimer != null) _silenceTimer.Dispose(); } catch (Exception) { }
            _silenceTimer = null;
            try { if (_loop != null) { _loop.StopRecording(); _loop.Dispose(); } } catch (Exception) { }
            _loop = null;
            try { if (_server != null) _server.Stop(); } catch (Exception) { }
            _server = null;
        }

        // ---------------------------------------------------------------- imagen

        private void CaptureLoop()
        {
            try
            {
                Rectangle bounds = Screen.PrimaryScreen.Bounds;
                int w0 = bounds.Width, h0 = bounds.Height;
                int shortSide = Math.Min(w0, h0);
                double k = _cfg.StreamRes < shortSide ? (double)_cfg.StreamRes / shortSide : 1.0;
                int w = Math.Max(16, ((int)Math.Round(w0 * k)) & ~1);
                int h = Math.Max(16, ((int)Math.Round(h0 * k)) & ~1);

                ImageCodecInfo jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
                var ep = new EncoderParameters(1);
                ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)_cfg.StreamQuality);

                using (var full = new Bitmap(w0, h0, PixelFormat.Format32bppRgb))
                using (var small = new Bitmap(w, h, PixelFormat.Format24bppRgb))
                using (var gf = Graphics.FromImage(full))
                using (var gs = Graphics.FromImage(small))
                using (var ms = new MemoryStream(128 * 1024))
                {
                    gs.InterpolationMode = InterpolationMode.Bilinear;
                    gs.CompositingQuality = CompositingQuality.HighSpeed;
                    gs.SmoothingMode = SmoothingMode.HighSpeed;

                    var sw = Stopwatch.StartNew();
                    long next = 0;
                    int fps = Math.Max(1, _cfg.StreamFps);
                    while (_run)
                    {
                        var srv = _server;
                        if (srv == null) break;
                        if (srv.VideoClients == 0)
                        {
                            Thread.Sleep(200);   // sin espectadores no se captura nada
                            next = sw.ElapsedMilliseconds;
                            continue;
                        }

                        gf.CopyFromScreen(bounds.X, bounds.Y, 0, 0, new Size(w0, h0), CopyPixelOperation.SourceCopy);
                        try
                        {
                            Point p = Cursor.Position;
                            Cursors.Arrow.Draw(gf, new Rectangle(p.X - bounds.X, p.Y - bounds.Y, 24, 24));
                        }
                        catch (Exception) { }
                        gs.DrawImage(full, 0, 0, w, h);

                        ms.SetLength(0);
                        small.Save(ms, jpeg, ep);
                        srv.PushFrame(ms.ToArray());

                        next += 1000 / fps;
                        long sleep = next - sw.ElapsedMilliseconds;
                        if (sleep > 0) Thread.Sleep((int)sleep); else next = sw.ElapsedMilliseconds;
                    }
                }
            }
            catch (Exception ex)
            {
                Post(() => { Say("La captura se detuvo: " + ex.Message); if (Running) Stop(); });
            }
        }

        // ---------------------------------------------------------------- audio

        private bool StartAudioCapture()
        {
            try
            {
                _loop = new WasapiLoopbackCapture();
                _srcRate = _loop.WaveFormat.SampleRate;
                _srcChannels = _loop.WaveFormat.Channels;
                _srcBits = _loop.WaveFormat.BitsPerSample;
                if (_srcBits != 32 && _srcBits != 16) { _loop.Dispose(); _loop = null; return false; }

                _loop.DataAvailable += OnAudio;
                _lastAudioTicks = Stopwatch.GetTimestamp();
                _loop.StartRecording();

                // Windows no entrega datos cuando no suena nada: se rellena con silencio
                // para que el audio del navegador no se corte ni se retrase.
                _silenceTimer = new System.Threading.Timer(_ =>
                {
                    var srv = _server;
                    if (srv == null) return;
                    double idleMs = (Stopwatch.GetTimestamp() - Interlocked.Read(ref _lastAudioTicks)) * 1000.0 / Stopwatch.Frequency;
                    if (idleMs > 250) srv.PushAudio(new byte[_srcRate / 10 * 4]);
                }, null, 100, 100);
                return true;
            }
            catch (Exception)
            {
                try { if (_loop != null) _loop.Dispose(); } catch (Exception) { }
                _loop = null;
                return false;
            }
        }

        private void OnAudio(object sender, WaveInEventArgs e)
        {
            var srv = _server;
            if (srv == null || e.BytesRecorded <= 0) return;
            Interlocked.Exchange(ref _lastAudioTicks, Stopwatch.GetTimestamp());

            int bytesPerSample = _srcBits / 8;
            int frameBytes = bytesPerSample * _srcChannels;
            int frames = e.BytesRecorded / frameBytes;
            var outBuf = new byte[frames * 4];   // estéreo de 16 bits
            for (int f = 0; f < frames; f++)
            {
                int o = f * frameBytes;
                short l = ReadSample(e.Buffer, o);
                short r = _srcChannels > 1 ? ReadSample(e.Buffer, o + bytesPerSample) : l;
                int d = f * 4;
                outBuf[d] = (byte)(l & 0xFF);
                outBuf[d + 1] = (byte)((l >> 8) & 0xFF);
                outBuf[d + 2] = (byte)(r & 0xFF);
                outBuf[d + 3] = (byte)((r >> 8) & 0xFF);
            }
            srv.PushAudio(outBuf);
        }

        private short ReadSample(byte[] b, int offset)
        {
            if (_srcBits == 32)
            {
                float v = BitConverter.ToSingle(b, offset);
                if (v > 1f) v = 1f; else if (v < -1f) v = -1f;
                return (short)(v * 32767f);
            }
            return BitConverter.ToInt16(b, offset);
        }

        // ---------------------------------------------------------------- red

        private static string ReadViewer()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("Vortice.viewer.html"))
            using (var r = new StreamReader(s, Encoding.UTF8))
                return r.ReadToEnd();
        }

        private static string RandomKey()
        {
            const string chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            var bytes = new byte[6];
            using (var rng = new RNGCryptoServiceProvider()) rng.GetBytes(bytes);
            var sb = new StringBuilder();
            foreach (byte b in bytes) sb.Append(chars[b % chars.Length]);
            return sb.ToString();
        }

        private static bool IsPrivate(string ip)
        {
            if (ip.StartsWith("192.168.") || ip.StartsWith("10.")) return true;
            if (ip.StartsWith("172."))
            {
                int second;
                string[] p = ip.Split('.');
                return p.Length > 1 && int.TryParse(p[1], out second) && second >= 16 && second <= 31;
            }
            return false;
        }

        /// <summary>Direcciones IPv4 de la PC, las de red local primero.</summary>
        public static List<string> LocalIps()
        {
            var res = new List<string>();
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback ||
                        ni.NetworkInterfaceType == NetworkInterfaceType.Tunnel) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily == AddressFamily.InterNetwork)
                            res.Add(ua.Address.ToString());
                    }
                }
            }
            catch (Exception) { }
            return res.OrderBy(ip => IsPrivate(ip) ? 0 : 1).ToList();
        }
    }
}
