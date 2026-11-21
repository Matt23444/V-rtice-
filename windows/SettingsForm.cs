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
        private readonly Action _applyBubble;
        private readonly Timer _timer = new Timer { Interval = 1000 };
        private bool _loading = true;

        private ComboBox _res, _fps, _vid, _aud;
        private CheckBox _sys, _mic, _bubble;
        private Button _rec;
        private Label _state;

        public SettingsForm(Config cfg, RecController ctl, Action applyBubble)
        {
            _cfg = cfg;
            _ctl = ctl;
            _applyBubble = applyBubble;

            Text = "Vórtice";
            Icon = Art.MakeIcon(32, false);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Font;
            Font = new Font("Segoe UI", 9.5f);
            ClientSize = new Size(380, 440);

            var table = new TableLayoutPanel
            {
                Dock = DockStyle.Fill,
                ColumnCount = 2,
                Padding = new Padding(14),
            };
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55));
            Controls.Add(table);

            _res = AddCombo(table, "Resolución",
                new[] { "Nativa", "1080p", "720p", "480p" }, new[] { 0, 1080, 720, 480 }, cfg.ShortSide);
            _fps = AddCombo(table, "Fotogramas por segundo",
                new[] { "15 fps", "24 fps", "30 fps", "60 fps" }, new[] { 15, 24, 30, 60 }, cfg.Fps);
            _vid = AddCombo(table, "Calidad de video",
                new[] { "1 Mbps", "2 Mbps", "3 Mbps", "5 Mbps", "8 Mbps" }, new[] { 1, 2, 3, 5, 8 }, cfg.VideoMbps);
            _sys = AddCheck(table, "Audio del sistema (lo que suena en la PC)", cfg.SysAudio);
            _mic = AddCheck(table, "Micrófono", cfg.Mic);
            _aud = AddCombo(table, "Calidad de audio",
                new[] { "96 kbps", "128 kbps", "160 kbps", "192 kbps" }, new[] { 96, 128, 160, 192 }, cfg.AudioKbps);
            _bubble = AddCheck(table, "Mostrar burbuja flotante", cfg.Bubble);

            _rec = new Button { Dock = DockStyle.Fill, Height = 46, Font = new Font("Segoe UI", 11f, FontStyle.Bold) };
            _rec.Click += (s, e) => _ctl.Toggle();
            table.Controls.Add(_rec, 0, table.RowCount);
            table.SetColumnSpan(_rec, 2);
            table.RowCount++;

            _state = new Label { Dock = DockStyle.Fill, AutoSize = true, Padding = new Padding(0, 8, 0, 0) };
            table.Controls.Add(_state, 0, table.RowCount);
            table.SetColumnSpan(_state, 2);
            table.RowCount++;

            var open = new Button { Text = "Abrir carpeta de videos", Dock = DockStyle.Fill, Height = 34 };
            open.Click += (s, e) =>
            {
                try { Directory.CreateDirectory(_cfg.OutDir); Process.Start("explorer.exe", "\"" + _cfg.OutDir + "\""); }
                catch (Exception) { }
            };
            table.Controls.Add(open, 0, table.RowCount);
            table.SetColumnSpan(open, 2);
            table.RowCount++;

            var tip = new Label
            {
                Dock = DockStyle.Fill,
                AutoSize = true,
                ForeColor = Color.DimGray,
                Padding = new Padding(0, 10, 0, 0),
                Text = "Atajo: Ctrl + Alt + R para grabar o detener.\n" +
                       "Para gastar menos CPU: 720p, 24 o 30 fps y 2 a 3 Mbps.\n" +
                       "Los ajustes no se pueden cambiar mientras se graba.",
            };
            table.Controls.Add(tip, 0, table.RowCount);
            table.SetColumnSpan(tip, 2);
            table.RowCount++;

            _loading = false;

            _ctl.StateChanged += RefreshState;
            _timer.Tick += (s, e) => RefreshState();
            VisibleChanged += (s, e) => RefreshState();
            RefreshState();
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
            t.Controls.Add(c, 0, t.RowCount);
            t.SetColumnSpan(c, 2);
            t.RowCount++;
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
            _cfg.Save();
            _aud.Enabled = !_ctl.Recording && (_sys.Checked || _mic.Checked);
            if (bubbleChanged) _applyBubble();
        }

        private void RefreshState()
        {
            bool rec = _ctl.Recording;
            _rec.Text = rec ? "■  Detener" : "●  Grabar";
            _rec.BackColor = rec ? Color.FromArgb(217, 48, 37) : SystemColors.Control;
            _rec.ForeColor = rec ? Color.White : SystemColors.ControlText;
            foreach (var c in new Control[] { _res, _fps, _vid, _sys, _mic }) c.Enabled = !rec;
            _aud.Enabled = !rec && (_sys.Checked || _mic.Checked);

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
