using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace Vortice
{
    /// <summary>Aplicación en la bandeja del sistema. Sin ventana abierta no consume nada.</summary>
    internal class TrayApp : ApplicationContext
    {
        private readonly Config _cfg = Config.Load();
        private readonly RecController _ctl;
        private readonly NotifyIcon _tray;
        private readonly Icon _idle;
        private readonly Icon _rec;
        private readonly HotkeyWindow _hotkey;
        private Bubble _bubble;
        private SettingsForm _settings;

        public TrayApp()
        {
            _ctl = new RecController(_cfg);
            _idle = Art.MakeIcon(32, false);
            _rec = Art.MakeIcon(32, true);

            var menu = new ContextMenuStrip();
            menu.Items.Add("Grabar / Detener   (Ctrl+Alt+R)", null, (s, e) => _ctl.Toggle());
            menu.Items.Add("Ajustes…", null, (s, e) => ShowSettings());
            menu.Items.Add("Abrir carpeta de videos", null, (s, e) => OpenFolder());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Salir", null, (s, e) => ExitApp());

            _tray = new NotifyIcon
            {
                Icon = _idle,
                Text = "Vórtice",
                Visible = true,
                ContextMenuStrip = menu,
            };
            _tray.MouseDoubleClick += (s, e) => { if (e.Button == MouseButtons.Left) ShowSettings(); };

            _ctl.StateChanged += OnState;
            _ctl.Info += msg => _tray.ShowBalloonTip(3000, "Vórtice", msg, ToolTipIcon.Info);

            _hotkey = new HotkeyWindow();
            _hotkey.Pressed += () => _ctl.Toggle();
            if (!_hotkey.Register())
                _tray.ShowBalloonTip(4000, "Vórtice", "El atajo Ctrl+Alt+R ya está en uso por otro programa.", ToolTipIcon.Warning);

            ApplyBubble();
            ShowSettings();   // la primera vez se muestra la ventana de ajustes
        }

        private void ShowSettings()
        {
            if (_settings == null) _settings = new SettingsForm(_cfg, _ctl, ApplyBubble);
            _settings.Show();
            _settings.WindowState = FormWindowState.Normal;
            _settings.Activate();
        }

        private void ApplyBubble()
        {
            if (_cfg.Bubble)
            {
                if (_bubble == null)
                    _bubble = new Bubble(_ctl, ShowSettings, ExitApp);
                if (!(_ctl.Recording && !_bubble.ExcludedFromCapture)) _bubble.Show();
            }
            else if (_bubble != null)
            {
                _bubble.Close();
                _bubble.Dispose();
                _bubble = null;
            }
        }

        private void OnState()
        {
            _tray.Icon = _ctl.Recording ? _rec : _idle;
            _tray.Text = _ctl.Recording ? "Vórtice — grabando" : "Vórtice";
            if (_bubble != null)
            {
                _bubble.Invalidate();
                // Si Windows no puede excluirla de la captura, se oculta mientras se graba.
                if (_ctl.Recording && !_bubble.ExcludedFromCapture) _bubble.Hide();
                else if (!_bubble.Visible) _bubble.Show();
            }
        }

        private void OpenFolder()
        {
            try { Directory.CreateDirectory(_cfg.OutDir); Process.Start("explorer.exe", "\"" + _cfg.OutDir + "\""); }
            catch (Exception) { }
        }

        private void ExitApp()
        {
            if (_ctl.Recording)
            {
                _tray.ShowBalloonTip(3000, "Vórtice", "Guardando la grabación antes de salir…", ToolTipIcon.Info);
                _ctl.Stop();
                var t = new Timer { Interval = 300 };
                int n = 0;
                t.Tick += (s, e) =>
                {
                    if (!_ctl.Recording || ++n > 30) { t.Stop(); Finish(); }
                };
                t.Start();
            }
            else
            {
                Finish();
            }
        }

        private void Finish()
        {
            try { _hotkey.Dispose(); } catch (Exception) { }
            _tray.Visible = false;
            _tray.Dispose();
            if (_bubble != null) { _bubble.Dispose(); _bubble = null; }
            if (_settings != null) { _settings.Dispose(); _settings = null; }
            ExitThread();
        }
    }
}
