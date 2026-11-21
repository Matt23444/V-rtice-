using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace Vortice
{
    /// <summary>Burbuja flotante: clic = grabar/detener, arrastrar = mover, clic derecho = menú.</summary>
    internal class Bubble : Form
    {
        private readonly RecController _ctl;
        private Point _downCursor;
        private Point _downLocation;
        private bool _pressed;
        private bool _dragged;

        /// <summary>true si Windows la excluye de la captura (Windows 10 2004 o superior).</summary>
        public bool ExcludedFromCapture { get; private set; }

        public Bubble(RecController ctl, Action openSettings, Action exit)
        {
            _ctl = ctl;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Size = new Size(52, 52);
            DoubleBuffered = true;
            BackColor = Color.FromArgb(20, 18, 31);

            var wa = Screen.PrimaryScreen.WorkingArea;
            Location = new Point(wa.Right - 72, wa.Top + 160);

            using (var path = new GraphicsPath())
            {
                path.AddEllipse(0, 0, Width, Height);
                Region = new Region(path);
            }

            var menu = new ContextMenuStrip();
            menu.Items.Add("Ajustes…", null, (s, e) => openSettings());
            menu.Items.Add("Salir", null, (s, e) => exit());
            ContextMenuStrip = menu;
        }

        protected override bool ShowWithoutActivation { get { return true; } }

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= 0x08000000;   // WS_EX_NOACTIVATE
                cp.ExStyle |= 0x00000080;   // WS_EX_TOOLWINDOW (no aparece en Alt+Tab)
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            try { ExcludedFromCapture = Native.SetWindowDisplayAffinity(Handle, Native.WDA_EXCLUDEFROMCAPTURE); }
            catch (Exception) { ExcludedFromCapture = false; }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Art.Draw(e.Graphics, Width, _ctl.Recording);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button != MouseButtons.Left) return;
            _pressed = true;
            _dragged = false;
            _downCursor = Cursor.Position;
            _downLocation = Location;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (!_pressed) return;
            int dx = Cursor.Position.X - _downCursor.X;
            int dy = Cursor.Position.Y - _downCursor.Y;
            if (Math.Abs(dx) > 4 || Math.Abs(dy) > 4) _dragged = true;
            if (_dragged) Location = new Point(_downLocation.X + dx, _downLocation.Y + dy);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (e.Button != MouseButtons.Left) return;
            bool wasClick = _pressed && !_dragged;
            _pressed = false;
            if (wasClick) _ctl.Toggle();
        }
    }
}
