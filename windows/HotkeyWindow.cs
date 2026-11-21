using System;
using System.Windows.Forms;

namespace Vortice
{
    /// <summary>Ventana invisible que recibe el atajo global Ctrl+Alt+R.</summary>
    internal class HotkeyWindow : NativeWindow, IDisposable
    {
        public event Action Pressed;

        public HotkeyWindow()
        {
            CreateHandle(new CreateParams());
        }

        public bool Register()
        {
            return Native.RegisterHotKey(Handle, 1,
                Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT, 0x52 /* R */);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == Native.WM_HOTKEY)
            {
                var h = Pressed;
                if (h != null) h();
            }
            base.WndProc(ref m);
        }

        public void Dispose()
        {
            try { Native.UnregisterHotKey(Handle, 1); } catch (Exception) { }
            DestroyHandle();
        }
    }
}
