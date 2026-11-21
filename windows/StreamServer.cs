using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace Vortice
{
    /// <summary>
    /// Servidor HTTP mínimo. Quien mira abre la dirección en un navegador:
    ///   /  visor   /video  imagen en vivo (MJPEG)   /audio  audio en vivo (WAV sin fin)   /info
    /// Todas las rutas exigen la clave "k" de la dirección.
    /// </summary>
    internal class StreamServer
    {
        private class Frame { public long Seq; public long Ts; public byte[] Data; }

        private readonly string _html;
        private readonly string _key;
        private readonly bool _hasAudio;
        private readonly int _rate;
        private readonly int _channels;

        private readonly object _lock = new object();
        private readonly LinkedList<Frame> _frames = new LinkedList<Frame>();
        private long _seq;

        private readonly List<BlockingCollection<byte[]>> _audio = new List<BlockingCollection<byte[]>>();
        private readonly List<TcpClient> _clients = new List<TcpClient>();
        private TcpListener _listener;
        private volatile bool _running;
        private int _videoClients;

        public int Port { get; private set; }
        public int VideoClients { get { return Volatile.Read(ref _videoClients); } }

        public StreamServer(string html, string key, bool hasAudio, int sampleRate, int channels)
        {
            _html = html; _key = key; _hasAudio = hasAudio; _rate = sampleRate; _channels = channels;
        }

        private static long NowMs() { return Stopwatch.GetTimestamp() * 1000 / Stopwatch.Frequency; }

        public int Start()
        {
            for (int p = 8080; p <= 8090; p++)
            {
                try
                {
                    var l = new TcpListener(IPAddress.Any, p);
                    l.Start();
                    _listener = l;
                    Port = p;
                    break;
                }
                catch (SocketException) { }
            }
            if (_listener == null) throw new IOException("No hay un puerto libre");
            _running = true;
            var t = new Thread(AcceptLoop) { IsBackground = true, Name = "stream-accept" };
            t.Start();
            return Port;
        }

        public void Stop()
        {
            _running = false;
            try { _listener.Stop(); } catch (Exception) { }
            lock (_lock) { Monitor.PulseAll(_lock); }
            lock (_clients)
            {
                foreach (var c in _clients) { try { c.Close(); } catch (Exception) { } }
                _clients.Clear();
            }
        }

        public void PushFrame(byte[] data)
        {
            long now = NowMs();
            lock (_lock)
            {
                _frames.AddLast(new Frame { Seq = ++_seq, Ts = now, Data = data });
                while (_frames.Count > 1 && now - _frames.First.Value.Ts > 4000) _frames.RemoveFirst();
                Monitor.PulseAll(_lock);
            }
        }

        public void PushAudio(byte[] chunk)
        {
            lock (_audio)
            {
                foreach (var q in _audio)
                {
                    if (!q.TryAdd(chunk))
                    {
                        byte[] dropped;
                        q.TryTake(out dropped);
                        q.TryAdd(chunk);
                    }
                }
            }
        }

        // ---------------------------------------------------------------- HTTP

        private void AcceptLoop()
        {
            while (_running)
            {
                TcpClient c;
                try { c = _listener.AcceptTcpClient(); }
                catch (Exception) { break; }
                lock (_clients) { _clients.Add(c); }
                var t = new Thread(() => Handle(c)) { IsBackground = true, Name = "stream-client" };
                t.Start();
            }
        }

        private void Handle(TcpClient c)
        {
            try
            {
                c.NoDelay = true;
                c.ReceiveTimeout = 5000;
                var stream = c.GetStream();
                var reader = new StreamReader(stream, Encoding.ASCII);
                string first = reader.ReadLine();
                if (first == null) return;
                while (true)
                {
                    string line = reader.ReadLine();
                    if (string.IsNullOrEmpty(line)) break;
                }
                c.ReceiveTimeout = 0;

                string[] parts = first.Split(' ');
                if (parts.Length < 2 || parts[0] != "GET") { Respond(stream, 405, "text/plain; charset=utf-8", "Método no permitido"); return; }
                string target = parts[1];
                int qi = target.IndexOf('?');
                string path = qi < 0 ? target : target.Substring(0, qi);
                var query = ParseQuery(qi < 0 ? "" : target.Substring(qi + 1));

                if (path == "/favicon.ico") { Respond(stream, 404, "text/plain", ""); return; }
                string k;
                if (!query.TryGetValue("k", out k) || k != _key)
                {
                    Respond(stream, 403, "text/plain; charset=utf-8",
                        "Acceso denegado. Usa la dirección completa que muestra la app (incluye ?k=...).");
                    return;
                }

                switch (path)
                {
                    case "/": Respond(stream, 200, "text/html; charset=utf-8", _html); break;
                    case "/info": Respond(stream, 200, "application/json", "{\"audio\":" + (_hasAudio ? "true" : "false") + "}"); break;
                    case "/video":
                        int delay = 0;
                        string d;
                        if (query.TryGetValue("delay", out d)) int.TryParse(d, out delay);
                        ServeVideo(stream, c, Math.Max(0, Math.Min(3500, delay)));
                        break;
                    case "/audio":
                        if (_hasAudio) ServeAudio(stream, c); else Respond(stream, 404, "text/plain", "Sin audio");
                        break;
                    default: Respond(stream, 404, "text/plain", "No encontrado"); break;
                }
            }
            catch (Exception) { /* el espectador se desconectó */ }
            finally
            {
                try { c.Close(); } catch (Exception) { }
                lock (_clients) { _clients.Remove(c); }
            }
        }

        private static Dictionary<string, string> ParseQuery(string q)
        {
            var m = new Dictionary<string, string>();
            foreach (var pair in q.Split(new[] { '&' }, StringSplitOptions.RemoveEmptyEntries))
            {
                int i = pair.IndexOf('=');
                string k = i < 0 ? pair : pair.Substring(0, i);
                string v = i < 0 ? "" : pair.Substring(i + 1);
                try { m[Uri.UnescapeDataString(k)] = Uri.UnescapeDataString(v); } catch (Exception) { }
            }
            return m;
        }

        private static void Respond(Stream s, int code, string type, string body)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(body);
            string status = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
            string head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type +
                          "\r\nContent-Length: " + bytes.Length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
            byte[] h = Encoding.ASCII.GetBytes(head);
            s.Write(h, 0, h.Length);
            s.Write(bytes, 0, bytes.Length);
            s.Flush();
        }

        private static void WriteAscii(Stream s, string text)
        {
            byte[] b = Encoding.ASCII.GetBytes(text);
            s.Write(b, 0, b.Length);
        }

        private void ServeVideo(Stream stream, TcpClient c, int delayMs)
        {
            WriteAscii(stream,
                "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\n\r\n");
            stream.Flush();
            Interlocked.Increment(ref _videoClients);
            try
            {
                long last = -1;
                while (_running && c.Connected)
                {
                    Frame f = NextFrame(last, delayMs);
                    if (f == null) continue;
                    WriteAscii(stream, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.Data.Length + "\r\n\r\n");
                    stream.Write(f.Data, 0, f.Data.Length);
                    WriteAscii(stream, "\r\n");
                    stream.Flush();
                    last = f.Seq;
                }
            }
            finally { Interlocked.Decrement(ref _videoClients); }
        }

        /// <summary>Fotograma más reciente que ya tenga [delayMs] de antigüedad y sea nuevo.</summary>
        private Frame NextFrame(long last, int delayMs)
        {
            lock (_lock)
            {
                long deadline = NowMs() + 1000;
                while (_running)
                {
                    long target = NowMs() - delayMs;
                    Frame cand = null;
                    foreach (var f in _frames)
                    {
                        if (delayMs <= 0 || f.Ts <= target) cand = f; else break;
                    }
                    if (cand != null && cand.Seq > last) return cand;
                    if (NowMs() > deadline) return null;
                    Monitor.Wait(_lock, 30);
                }
                return null;
            }
        }

        private void ServeAudio(Stream stream, TcpClient c)
        {
            WriteAscii(stream,
                "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n");
            byte[] wav = WavHeader();
            stream.Write(wav, 0, wav.Length);
            stream.Flush();
            var q = new BlockingCollection<byte[]>(new ConcurrentQueue<byte[]>(), 48);
            lock (_audio) { _audio.Add(q); }
            try
            {
                while (_running && c.Connected)
                {
                    byte[] chunk;
                    if (!q.TryTake(out chunk, 500)) continue;
                    stream.Write(chunk, 0, chunk.Length);
                    stream.Flush();
                }
            }
            finally { lock (_audio) { _audio.Remove(q); } }
        }

        /// <summary>Cabecera WAV de longitud indefinida (PCM de 16 bits).</summary>
        private byte[] WavHeader()
        {
            var ms = new MemoryStream();
            var w = new BinaryWriter(ms);
            w.Write(Encoding.ASCII.GetBytes("RIFF")); w.Write(-1); w.Write(Encoding.ASCII.GetBytes("WAVE"));
            w.Write(Encoding.ASCII.GetBytes("fmt ")); w.Write(16); w.Write((short)1); w.Write((short)_channels);
            w.Write(_rate); w.Write(_rate * _channels * 2); w.Write((short)(_channels * 2)); w.Write((short)16);
            w.Write(Encoding.ASCII.GetBytes("data")); w.Write(-1);
            w.Flush();
            return ms.ToArray();
        }
    }
}
