// Single-file LogAnalyzer.exe: a small launcher with the whole app (Java runtime included) appended to it.
//
// On first start it unpacks the app once into %LOCALAPPDATA%\LogAnalyzer\app\<version> (no installer, no admin
// rights), showing a progress window in the app's colours; later starts go straight to the unpacked app. Older
// unpacked versions are removed. All command-line arguments are passed on to the app.
//
// Layout of the exe: [this launcher][zip of the jpackage app image][8-byte zip length][8-byte "LAPAYLD1"]
// Built with the C# compiler that ships with Windows (.NET Framework 4.x), so the language level is C# 5.

using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Threading;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Log Analyzer")]
[assembly: System.Reflection.AssemblyProduct("Log Analyzer")]
[assembly: System.Reflection.AssemblyDescription("Log analytics for Siebel CRM and banking services")]

static class Launcher
{
    const string Magic = "LAPAYLD1";
    static readonly Color Bg = Color.FromArgb(15, 23, 42), Fg = Color.FromArgb(226, 232, 240),
        Dim = Color.FromArgb(148, 163, 184), Accent = Color.FromArgb(99, 102, 241), Track = Color.FromArgb(30, 41, 59);

    [STAThread]
    static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            string self = Process.GetCurrentProcess().MainModule.FileName;
            long offset, length;
            if (!FindPayload(self, out offset, out length))
            {
                return Fail("This copy of LogAnalyzer.exe is incomplete. Download it again.", null);
            }
            string root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "LogAnalyzer", "app");
            string target = Path.Combine(root, AppVersion.Value + "-" + length.ToString("x"));
            string exe = Path.Combine(target, "LogAnalyzer.exe");

            using (Mutex m = new Mutex(false, "Local\\LogAnalyzer-unpack"))
            {
                try { m.WaitOne(); } catch (AbandonedMutexException) { }
                try
                {
                    if (!File.Exists(Path.Combine(target, ".complete")) || !File.Exists(exe))
                    {
                        string error = Unpack(self, offset, length, root, target);
                        if (error != null) return Fail("Log Analyzer could not be prepared on this computer.", error);
                    }
                }
                finally { m.ReleaseMutex(); }
            }
            CleanOldVersions(root, target);

            ProcessStartInfo psi = new ProcessStartInfo(exe, JoinArgs(args));
            psi.UseShellExecute = false;
            psi.WorkingDirectory = target;
            using (Process p = Process.Start(psi))
            {
                p.WaitForExit();
                return p.ExitCode;
            }
        }
        catch (Exception e)
        {
            return Fail("Log Analyzer could not be started.", e.Message);
        }
    }

    // ------------------------------------------------------------------ payload

    static bool FindPayload(string self, out long offset, out long length)
    {
        offset = length = 0;
        using (FileStream fs = new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
        {
            if (fs.Length < 16) return false;
            byte[] tail = new byte[16];
            fs.Seek(-16, SeekOrigin.End);
            ReadFully(fs, tail);
            if (Encoding.ASCII.GetString(tail, 8, 8) != Magic) return false;
            length = BitConverter.ToInt64(tail, 0);
            offset = fs.Length - 16 - length;
            return length > 0 && offset > 0;
        }
    }

    /** Unpacks into a temporary folder next to the target, then renames it, so a half-unpacked app is never used. */
    static string Unpack(string self, long offset, long length, string root, string target)
    {
        Directory.CreateDirectory(root);
        string temp = target + ".partial-" + Process.GetCurrentProcess().Id;
        string error = null;
        ProgressForm form = new ProgressForm();
        Thread worker = new Thread(delegate ()
        {
            try
            {
                if (Directory.Exists(temp)) Directory.Delete(temp, true);
                using (FileStream fs = new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (ZipArchive zip = new ZipArchive(new SubStream(fs, offset, length), ZipArchiveMode.Read))
                {
                    long total = 0, done = 0;
                    foreach (ZipArchiveEntry e in zip.Entries) total += e.Length;
                    byte[] buf = new byte[1 << 16];
                    string fullTemp = Path.GetFullPath(temp) + Path.DirectorySeparatorChar;
                    foreach (ZipArchiveEntry e in zip.Entries)
                    {
                        string name = e.FullName.Replace('/', Path.DirectorySeparatorChar);
                        // the archive holds a single top-level folder (LogAnalyzer\...): unpack its contents
                        int slash = name.IndexOf(Path.DirectorySeparatorChar);
                        if (slash >= 0 && name.Substring(0, slash).Equals("LogAnalyzer", StringComparison.OrdinalIgnoreCase)) name = name.Substring(slash + 1);
                        if (name.Length == 0) continue;
                        string dest = Path.GetFullPath(Path.Combine(temp, name));
                        if (!dest.StartsWith(fullTemp, StringComparison.OrdinalIgnoreCase)) continue; // never outside the folder
                        if (name.EndsWith(Path.DirectorySeparatorChar.ToString())) { Directory.CreateDirectory(dest); continue; }
                        Directory.CreateDirectory(Path.GetDirectoryName(dest));
                        using (Stream src = e.Open())
                        using (FileStream outFs = new FileStream(dest, FileMode.Create, FileAccess.Write))
                        {
                            int n;
                            while ((n = src.Read(buf, 0, buf.Length)) > 0)
                            {
                                outFs.Write(buf, 0, n);
                                done += n;
                                form.Report(total == 0 ? 0 : (int)(done * 1000 / total));
                            }
                        }
                    }
                }
                File.WriteAllText(Path.Combine(temp, ".complete"), AppVersion.Value);
                if (Directory.Exists(target)) Directory.Delete(target, true);
                Directory.Move(temp, target);
            }
            catch (Exception e)
            {
                error = e.Message;
                try { if (Directory.Exists(temp)) Directory.Delete(temp, true); } catch (Exception) { }
            }
            form.Finish();
        });
        worker.IsBackground = true;
        worker.Start();
        Application.Run(form);
        worker.Join();
        return error;
    }

    static void CleanOldVersions(string root, string keep)
    {
        try
        {
            foreach (string dir in Directory.GetDirectories(root))
            {
                if (string.Equals(Path.GetFullPath(dir), Path.GetFullPath(keep), StringComparison.OrdinalIgnoreCase)) continue;
                try { Directory.Delete(dir, true); } catch (Exception) { } // still running: removed next time
            }
        }
        catch (Exception) { }
    }

    static void ReadFully(Stream s, byte[] b)
    {
        int read = 0;
        while (read < b.Length)
        {
            int n = s.Read(b, read, b.Length - read);
            if (n <= 0) throw new EndOfStreamException();
            read += n;
        }
    }

    /** Arguments quoted the way Windows programs split them again. */
    static string JoinArgs(string[] args)
    {
        StringBuilder sb = new StringBuilder();
        foreach (string a in args)
        {
            if (sb.Length > 0) sb.Append(' ');
            if (a.Length > 0 && a.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0) { sb.Append(a); continue; }
            sb.Append('"');
            int slashes = 0;
            foreach (char c in a)
            {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') sb.Append('\\', slashes * 2 + 1);
                else sb.Append('\\', slashes);
                slashes = 0;
                sb.Append(c);
            }
            sb.Append('\\', slashes * 2);
            sb.Append('"');
        }
        return sb.ToString();
    }

    static int Fail(string message, string detail)
    {
        Application.Run(new MessageForm(message, detail));
        return 1;
    }

    // ------------------------------------------------------------------ windows in the app's colours

    static Form Window(int height)
    {
        Form f = new Form();
        f.FormBorderStyle = FormBorderStyle.None;
        f.StartPosition = FormStartPosition.CenterScreen;
        f.BackColor = Bg;
        f.ForeColor = Fg;
        f.ClientSize = new Size(440, height);
        f.Text = "Log Analyzer";
        f.ShowInTaskbar = true;
        f.Font = new Font("Segoe UI", 9.5f);
        try { f.Icon = Icon.ExtractAssociatedIcon(Process.GetCurrentProcess().MainModule.FileName); } catch (Exception) { }
        f.Paint += delegate (object s, PaintEventArgs e)
        {
            using (Pen p = new Pen(Color.FromArgb(51, 65, 85))) e.Graphics.DrawRectangle(p, 0, 0, f.ClientSize.Width - 1, f.ClientSize.Height - 1);
        };
        Label title = new Label();
        title.Text = "Log Analyzer";
        title.Font = new Font("Segoe UI Semibold", 13f);
        title.ForeColor = Fg;
        title.AutoSize = true;
        title.Location = new Point(28, 24);
        f.Controls.Add(title);
        return f;
    }

    sealed class ProgressForm : Form
    {
        readonly Panel bar = new Panel();
        readonly Label status = new Label();
        volatile int permille;
        volatile bool finished;
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();

        public ProgressForm()
        {
            Form w = Window(150);
            FormBorderStyle = w.FormBorderStyle; StartPosition = w.StartPosition; BackColor = w.BackColor; ForeColor = w.ForeColor;
            ClientSize = w.ClientSize; Text = w.Text; Font = w.Font; Icon = w.Icon;
            while (w.Controls.Count > 0) Controls.Add(w.Controls[0]);
            Paint += delegate (object s, PaintEventArgs e)
            {
                using (Pen p = new Pen(Color.FromArgb(51, 65, 85))) e.Graphics.DrawRectangle(p, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
            };

            status.Text = "Getting ready for first use. This takes a moment and only happens once.";
            status.ForeColor = Dim;
            status.AutoSize = false;
            status.Size = new Size(384, 36);
            status.Location = new Point(28, 58);
            Controls.Add(status);

            Panel track = new Panel();
            track.BackColor = Track;
            track.Location = new Point(28, 104);
            track.Size = new Size(384, 6);
            bar.BackColor = Accent;
            bar.Size = new Size(0, 6);
            track.Controls.Add(bar);
            Controls.Add(track);

            timer.Interval = 50;
            timer.Tick += delegate
            {
                bar.Width = 384 * permille / 1000;
                if (finished) { timer.Stop(); Close(); }
            };
            timer.Start();
        }

        public void Report(int p) { permille = Math.Max(0, Math.Min(1000, p)); }
        public void Finish() { finished = true; }
    }

    sealed class MessageForm : Form
    {
        public MessageForm(string message, string detail)
        {
            Form w = Window(detail == null ? 160 : 200);
            FormBorderStyle = w.FormBorderStyle; StartPosition = w.StartPosition; BackColor = w.BackColor; ForeColor = w.ForeColor;
            ClientSize = w.ClientSize; Text = w.Text; Font = w.Font; Icon = w.Icon;
            while (w.Controls.Count > 0) Controls.Add(w.Controls[0]);
            Paint += delegate (object s, PaintEventArgs e)
            {
                using (Pen p = new Pen(Color.FromArgb(51, 65, 85))) e.Graphics.DrawRectangle(p, 0, 0, ClientSize.Width - 1, ClientSize.Height - 1);
            };
            Label msg = new Label();
            msg.Text = message;
            msg.ForeColor = Fg;
            msg.AutoSize = false;
            msg.Size = new Size(384, 40);
            msg.Location = new Point(28, 58);
            Controls.Add(msg);
            if (detail != null)
            {
                Label d = new Label();
                d.Text = detail;
                d.ForeColor = Dim;
                d.AutoSize = false;
                d.Size = new Size(384, 40);
                d.Location = new Point(28, 96);
                Controls.Add(d);
            }
            Button close = new Button();
            close.Text = "Close";
            close.FlatStyle = FlatStyle.Flat;
            close.FlatAppearance.BorderSize = 0;
            close.BackColor = Accent;
            close.ForeColor = Color.White;
            close.Size = new Size(96, 32);
            close.Location = new Point(ClientSize.Width - 28 - 96, ClientSize.Height - 24 - 32);
            close.Click += delegate { Close(); };
            Controls.Add(close);
            AcceptButton = close;
            CancelButton = close;
        }
    }
}

/** A read-only window onto part of another stream (the zip appended to the exe). */
sealed class SubStream : Stream
{
    readonly Stream inner;
    readonly long start, length;
    long pos;

    public SubStream(Stream inner, long start, long length) { this.inner = inner; this.start = start; this.length = length; }

    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return length; } }
    public override long Position { get { return pos; } set { pos = value; } }
    public override void Flush() { }
    public override void SetLength(long value) { throw new NotSupportedException(); }
    public override void Write(byte[] buffer, int offset, int count) { throw new NotSupportedException(); }

    public override int Read(byte[] buffer, int offset, int count)
    {
        long left = length - pos;
        if (left <= 0) return 0;
        if (count > left) count = (int)left;
        inner.Seek(start + pos, SeekOrigin.Begin);
        int n = inner.Read(buffer, offset, count);
        pos += n;
        return n;
    }

    public override long Seek(long offset, SeekOrigin origin)
    {
        long p = origin == SeekOrigin.Begin ? offset : origin == SeekOrigin.Current ? pos + offset : length + offset;
        if (p < 0) throw new IOException("Seek before start");
        pos = p;
        return pos;
    }
}
