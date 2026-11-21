using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

namespace Vortice
{
    /// <summary>Dibuja el logotipo (vórtice con punto de grabación) con GDI+.</summary>
    internal static class Art
    {
        public static void Draw(Graphics g, int s, bool recording)
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float u = s / 108f;
            using (var bg = new SolidBrush(Color.FromArgb(255, 20, 18, 31)))
                g.FillEllipse(bg, 0, 0, s - 1, s - 1);

            Arc(g, u, Color.FromArgb(124, 77, 255), 28, 270);
            Arc(g, u, Color.FromArgb(64, 196, 255), 19, 0);
            Arc(g, u, Color.FromArgb(179, 136, 255), 10, 90);

            if (recording)
            {
                using (var ring = new Pen(Color.FromArgb(255, 59, 78), 4 * u))
                    g.DrawEllipse(ring, 2 * u, 2 * u, s - 1 - 4 * u, s - 1 - 4 * u);
                g.FillRectangle(Brushes.White, 50 * u, 50 * u, 8 * u, 8 * u);
            }
            else
            {
                using (var dot = new SolidBrush(Color.FromArgb(255, 59, 78)))
                    g.FillEllipse(dot, 49.5f * u, 49.5f * u, 9 * u, 9 * u);
            }
        }

        private static void Arc(Graphics g, float u, Color c, float r, float start)
        {
            using (var p = new Pen(c, 5 * u))
            {
                p.StartCap = LineCap.Round;
                p.EndCap = LineCap.Round;
                g.DrawArc(p, (54 - r) * u, (54 - r) * u, 2 * r * u, 2 * r * u, start, 270);
            }
        }

        public static Icon MakeIcon(int size, bool recording)
        {
            using (var bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb))
            {
                using (var g = Graphics.FromImage(bmp))
                {
                    g.Clear(Color.Transparent);
                    Draw(g, size, recording);
                }
                IntPtr h = bmp.GetHicon();
                try { return (Icon)Icon.FromHandle(h).Clone(); }
                finally { Native.DestroyIcon(h); }
            }
        }
    }
}
