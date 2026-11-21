using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace Vortice
{
    internal class SettingsForm : Form
    {
        private readonly Config _cfg;
        private readonly RecController _ctl;
        private readonly PcStreamer _stream;
        private readonly Action _applyBubble;
        private readonly Timer _timer = new Timer { Interval = 1000 };
        private bool _loading = true;

        // Grabar
        private ComboBox _res, _fps, _vid, _aud;
        private CheckBox _sys, _mic, _bubble;
        private Button _rec;
        private Label _state;

        // Transmitir
        private ComboBox _sres, _sfps, _sq;
        private CheckBox _saud;
        private Button _strBtn, _copy;
        private TextBox _url;
        private Label _strState;

        public SettingsForm(Config cfg, RecController ctl, PcStreamer stream, Action applyBubble)
        {
            _cfg = cfg;
            _ctl = ctl;
            _stream = stream;
            _applyBubble = applyBubble;

            Text = "Vórtice";
            Icon = Art.MakeIcon(32, false);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Font;
            Font = new Font("Segoe UI", 9.5f);
            ClientSize = new Size(400, 520);

            var tabs = new TabControl { Dock = DockStyle.Fill };
            var tabRec = new TabPage("Grabar");
            var tabStr = new TabPage("Transmitir");
            tabs.TabPages.Add(tabRec);
            tabs.TabPages.Add(tabStr);
            Controls.Add(tabs);

            BuildRecordTab(tabRec);
            BuildStreamTab(tabStr);

            _loading = false;

            _ctl.StateChanged += RefreshState;
            _stream.StateChanged += RefreshState;
            _timer.Tick += (s, e) => RefreshState();
            VisibleChanged += (s, e) => RefreshState();
            RefreshState();
        }

        private static TableLayoutPanel NewTable()
        {
            var t = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, Padding = new Padding(12), AutoScroll = true };
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
            t.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55));
            return t;
        }

        private void BuildRecordTab(TabPage page)
        {
            var table = NewTable();
            page.Controls.Add(table);

            _res = AddCombo(table, "Resolución",
                new[] { "Nativa", "1080p", "720p", "480p" }, new[] { 0, 1080, 720, 480 }, _cfg.ShortSide);
            _fps = AddCombo(table, "Fotogramas por segundo",
                new[] { "15 fps", "24 fps", "30 fps", "60 fps" }, new[] { 15, 24, 30, 60 }, _cfg.Fps);
            _vid = AddCombo(table, "Calidad de video",
                new[] { "1 Mbps", "2 Mbps", "3 Mbps", "5 Mbps", "8 Mbps" }, new[] { 1, 2, 3, 5, 8 }, _cfg.VideoMbps);
            _sys = AddCheck(table, "Audio del sistema (lo que suena en la PC)", _cfg.SysAudio);
            _mic = AddCheck(table, "Micrófono", _cfg.Mic);
            _aud = AddCombo(table, "Calidad de audio",
                new[] { "96 kbps", "128 kbps", "160 kbps", "192 kbps" }, new[] { 96, 128, 160, 192 }, _cfg.AudioKbps);
            _bubble = AddCheck(table, "Mostrar burbuja flotante", _cfg.Bubble);

            _rec = new Button { Dock = DockStyle.Fill, Height = 46, Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
            _rec.Click += (s, e) => _ctl.Toggle();
            AddWide(table, _rec);

            _state = new Label { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 8, 0, 0) };
            AddWide(table, _state);

            var open = new Button { Text = "Abrir carpeta de videos", Dock = DockStyle.Fill, Height = 34 };
            open.Click += (s, e) =>
            {
                try { Directory.CreateDirectory(_cfg.OutDir); Process.Start("explorer.exe", "\"" + _cfg.OutDir + "\""); }
                catch (Exception) { }
            };
            AddWide(table, open);

            AddWide(table, new Label
            {
                AutoSize = true,
                ForeColor = Color.DimGray,
                Padding = new Padding(0, 10, 0, 0),
                MaximumSize = new Size(340, 0),
                Text = "Atajo: Ctrl + Alt + R para grabar o detener.\n" +
                       "Para gastar menos CPU: 720p, 24 o 30 fps y 2 a 3 Mbps.\n" +
                       "Los ajustes no se pueden cambiar mientras se graba.",
            });
        }

        private void BuildStreamTab(TabPage page)
        {
            var table = NewTable();
            page.Controls.Add(table);

            _sres = AddCombo(table, "Resolución",
                new[] { "360p", "480p", "720p" }, new[] { 360, 480, 720 }, _cfg.StreamRes);
            _sfps = AddCombo(table, "Fluidez",
                new[] { "8 fps", "12 fps", "15 fps", "24 fps" }, new[] { 8, 12, 15, 24 }, _cfg.StreamFps);
            _sq = AddCombo(table, "Calidad de imagen",
                new[] { "Baja", "Media", "Alta", "Máxima" }, new[] { 40, 55, 70, 85 }, _cfg.StreamQuality);
            _saud = AddCheck(table, "Transmitir el audio del sistema", _cfg.StreamAudio);

            _strBtn = new Button { Dock = DockStyle.Fill, Height = 46, Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
            _strBtn.Click += (s, e) => _stream.Toggle();
            AddWide(table, _strBtn);

            _strState = new Label { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 8, 0, 4) };
            AddWide(table, _strState);

            _url = new TextBox { ReadOnly = true, Dock = DockStyle.Fill, Visible = false };
            AddWide(table, _url);

            _copy = new Button { Text = "Copiar enlace", Dock = DockStyle.Fill, Height = 34, Visible = false };
            _copy.Click += (s, e) =>
            {
                if (!string.IsNullOrEmpty(_stream.Url))
                {
                    try { Clipboard.SetText(_stream.Url); } catch (Exception) { }
                }
            };
            AddWide(table, _copy);

            AddWide(table, new Label
            {
                AutoSize = true,
                ForeColor = Color.DimGray,
                Padding = new Padding(0, 10, 0, 0),
                MaximumSize = new Size(340, 0),
                Text = "El otro dispositivo (celular, tablet u otra PC) debe estar en la misma red Wi-Fi " +
                       "y abrir el enlace en el navegador. No necesita instalar nada.\n" +
                       "La primera vez, Windows pedirá permiso en el firewall: marca «Redes privadas».\n" +
                       "Sin nadie mirando, la transmisión no consume CPU.",
            });
        }

        private static void AddWide(TableLayoutPanel t, Control c)
        {
            t.Controls.Add(c, 0, t.RowCount);
            t.SetColumnSpan(c, 2);
            t.RowCount++;
        }

        private ComboBox AddCombo(TableLayoutPanel t, string label, string[] names, int[] values, int current)
        {
            var l = new Label { Text = label, AutoSize = true, Anchor = AnchorStyles.Left, Padding = new Padding(0, 8, 0, 8) };
            var cb = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Dock = DockStyle.Fill, Tag = values };
            cb.Items.AddRange(names);
            int idx = Array.IndexOf(values, current);
            cb.SelectedIndex = idx < 0 ? 0 : idx;
            cb.SelectedIndexChanged += (s, e) => Apply();
            t.Controls.Add(l, 0, t.RowCount);
            t.Controls.Add(cb, 1, t.RowCount);
            t.RowCount++;
            return cb;
        }

        private CheckBox AddCheck(TableLayoutPanel t, string label, bool value)
        {
            var c = new CheckBox { Text = label, Checked = value, AutoSize = true, Padding = new Padding(0, 6, 0, 6) };
            c.CheckedChanged += (s, e) => Apply();
            AddWide(t, c);
            return c;
        }

        private static int Val(ComboBox cb) { return ((int[])cb.Tag)[cb.SelectedIndex]; }

        private void Apply()
        {
            if (_loading) return;
            _cfg.ShortSide = Val(_res);
            _cfg.Fps = Val(_fps);
            _cfg.VideoMbps = Val(_vid);
            _cfg.AudioKbps = Val(_aud);
            _cfg.SysAudio = _sys.Checked;
            _cfg.Mic = _mic.Checked;
            bool bubbleChanged = _cfg.Bubble != _bubble.Checked;
            _cfg.Bubble = _bubble.Checked;
            _cfg.StreamRes = Val(_sres);
            _cfg.StreamFps = Val(_sfps);
            _cfg.StreamQuality = Val(_sq);
            _cfg.StreamAudio = _saud.Checked;
            _cfg.Save();
            _aud.Enabled = !_ctl.Recording && (_sys.Checked || _mic.Checked);
            if (bubbleChanged) _applyBubble();
        }

        private void RefreshState()
        {
            bool rec = _ctl.Recording;
            bool str = _stream.Running;

            _rec.Text = rec ? "■  Detener" : "●  Grabar";
            _rec.BackColor = rec ? Color.FromArgb(217, 48, 37) : SystemColors.Control;
            _rec.ForeColor = rec ? Color.White : SystemColors.ControlText;
            foreach (var c in new Control[] { _res, _fps, _vid, _sys, _mic }) c.Enabled = !rec;
            _aud.Enabled = !rec && (_sys.Checked || _mic.Checked);

            _strBtn.Text = str ? "■  Detener transmisión" : "▶  Transmitir pantalla";
            _strBtn.BackColor = str ? Color.FromArgb(26, 115, 232) : SystemColors.Control;
            _strBtn.ForeColor = str ? Color.White : SystemColors.ControlText;
            foreach (var c in new Control[] { _sres, _sfps, _sq, _saud }) c.Enabled = !str;
            _url.Visible = str;
            _copy.Visible = str;
            if (str)
            {
                _url.Text = _stream.Url;
                _strState.Text = _stream.AllUrls.Count > 1
                    ? "Transmitiendo. Abre este enlace en el otro dispositivo\n(si no abre, prueba con otra de tus direcciones: " + (_stream.AllUrls.Count - 1) + " más)."
                    : "Transmitiendo. Abre este enlace en el otro dispositivo:";
            }
            else
            {
                _strState.Text = "Listo para transmitir";
            }

            if (rec)
            {
                var t = DateTime.Now - _ctl.StartedAt;
                _state.Text = string.Format("Grabando…  {0:00}:{1:00}:{2:00}", (int)t.TotalHours, t.Minutes, t.Seconds);
                if (Visible && !_timer.Enabled) _timer.Start();
            }
            else
            {
                _state.Text = "Listo para grabar";
                _timer.Stop();
            }
            if (!Visible) _timer.Stop();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Cerrar la ventana solo la oculta: el programa sigue en la bandeja.
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnFormClosing(e);
        }
    }
}
