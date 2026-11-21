using System;
using System.Threading;
using System.Windows.Forms;

namespace Vortice
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            bool created;
            using (var mutex = new Mutex(true, "Vortice.SingleInstance", out created))
            {
                if (!created)
                {
                    MessageBox.Show("Vórtice ya está en ejecución. Busca su icono junto al reloj.", "Vórtice");
                    return;
                }
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
                Application.Run(new TrayApp());
            }
        }
    }
}
