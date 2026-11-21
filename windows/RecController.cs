using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Windows.Forms;
using ScreenRecorderLib;

namespace Vortice
{
    /// <summary>Envuelve ScreenRecorderLib. Usa el codificador H.264 por hardware de Windows.</summary>
    internal class RecController
    {
        private readonly Config _cfg;
        private readonly SynchronizationContext _ui;
        private Recorder _rec;

        public bool Recording { get; private set; }
        public DateTime StartedAt { get; private set; }
        public string LastFile { get; private set; }

        public event Action StateChanged;
        public event Action<string> Info;

        public RecController(Config cfg)
        {
            _cfg = cfg;
            _ui = SynchronizationContext.Current;
        }

        public void Toggle()
        {
            if (Recording) Stop(); else Start();
        }

        private void Raise() { var h = StateChanged; if (h != null) h(); }
        private void Say(string m) { var h = Info; if (h != null) h(m); }

        private static int Even(double v) { int n = (int)Math.Round(v); return Math.Max(2, n - (n % 2)); }

        private RecorderOptions Build()
        {
            var output = new OutputOptions
            {
                RecorderMode = RecorderMode.Video,
                Stretch = StretchMode.Uniform,
            };
            if (_cfg.ShortSide > 0)
            {
                var b = Screen.PrimaryScreen.Bounds;
                int shortSide = Math.Min(b.Width, b.Height);
                if (_cfg.ShortSide < shortSide)
                {
                    double k = (double)_cfg.ShortSide / shortSide;
                    output.OutputFrameSize = new ScreenSize(Even(b.Width * k), Even(b.Height * k));
                }
            }

            var audioSources = new List<AudioSourceBase>();
            if (_cfg.SysAudio) audioSources.Add(LoopbackAudioSource.Default);
            if (_cfg.Mic) audioSources.Add(CaptureAudioSource.Default);

            AudioBitrate abr = AudioBitrate.bitrate_96kbps;
            if (_cfg.AudioKbps >= 192) abr = AudioBitrate.bitrate_192kbps;
            else if (_cfg.AudioKbps >= 160) abr = AudioBitrate.bitrate_160kbps;
            else if (_cfg.AudioKbps >= 128) abr = AudioBitrate.bitrate_128kbps;

            return new RecorderOptions
            {
                SourceOptions = new SourceOptions
                {
                    RecordingSources = new List<RecordingSourceBase>
                    {
                        new DisplayRecordingSource(DisplayRecordingSource.MainMonitor)
                    }
                },
                OutputOptions = output,
                AudioOptions = new AudioOptions
                {
                    IsAudioEnabled = audioSources.Count > 0,
                    AudioSources = audioSources,
                    Bitrate = abr,
                    Channels = AudioChannels.Stereo,
                },
                VideoEncoderOptions = new VideoEncoderOptions
                {
                    Bitrate = _cfg.VideoMbps * 1000 * 1000,
                    Framerate = _cfg.Fps,
                    IsFixedFramerate = false,           // si la pantalla no cambia, no se codifica nada
                    Encoder = new H264VideoEncoder
                    {
                        BitrateMode = H264BitrateControlMode.UnconstrainedVBR,
                        EncoderProfile = H264Profile.Main,
                    },
                    IsHardwareEncodingEnabled = true,   // usa la GPU/Quick Sync si existe
                    IsLowLatencyEnabled = false,
                    IsMp4FastStartEnabled = false,
                    IsFragmentedMp4Enabled = false,
                },
            };
        }

        public void Start()
        {
            if (Recording) return;
            try
            {
                Directory.CreateDirectory(_cfg.OutDir);
                string path = Path.Combine(_cfg.OutDir,
                    "Vortice_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".mp4");

                _rec = Recorder.CreateRecorder(Build());
                _rec.OnRecordingComplete += (s, e) => Post(() =>
                {
                    Recording = false;
                    LastFile = e.FilePath;
                    Raise();
                    Say("Guardado: " + Path.GetFileName(e.FilePath));
                    Cleanup();
                });
                _rec.OnRecordingFailed += (s, e) => Post(() =>
                {
                    Recording = false;
                    Raise();
                    Say("Error al grabar: " + e.Error);
                    Cleanup();
                });

                Recording = true;
                StartedAt = DateTime.Now;
                _rec.Record(path);
                Raise();
            }
            catch (Exception ex)
            {
                Recording = false;
                Raise();
                Say("No se pudo iniciar: " + ex.Message);
                Cleanup();
            }
        }

        public void Stop()
        {
            if (!Recording || _rec == null) return;
            try { _rec.Stop(); }
            catch (Exception ex)
            {
                Recording = false;
                Raise();
                Say("No se pudo detener bien: " + ex.Message);
                Cleanup();
            }
        }

        private void Post(Action a)
        {
            if (_ui != null) _ui.Post(_ => a(), null); else a();
        }

        private void Cleanup()
        {
            try { if (_rec != null) _rec.Dispose(); } catch (Exception) { }
            _rec = null;
        }
    }
}
