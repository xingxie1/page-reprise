using System;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Text.RegularExpressions;

internal static class InstallUpdate
{
    private static readonly string Data = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PageReprise", "data", "updates");

    private static bool InCache(string file)
    {
        return String.Equals(Path.GetDirectoryName(Path.GetFullPath(file)), Data, StringComparison.OrdinalIgnoreCase);
    }

    [STAThread]
    private static int Main(string[] args)
    {
        int parentId;
        if (args.Length != 4 || !Int32.TryParse(args[0], out parentId) ||
            !InCache(args[1]) || !InCache(args[3]) ||
            !Regex.IsMatch(Path.GetFileName(args[1]), @"^PageReprise-\d+\.\d+\.\d+-Windows-x64\.exe$") ||
            !Regex.IsMatch(args[2], "^[0-9a-f]{64}$") ||
            !Regex.IsMatch(Path.GetFileName(args[3]), @"^[0-9a-f-]{36}\.ready$")) return 1;

        bool restart = false;
        var installed = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "PageRepriseDesktop", "PageReprise.exe");
        try
        {
            using (var gate = new FileStream(Path.Combine(Data, "install.lock"), FileMode.OpenOrCreate,
                       FileAccess.Write, FileShare.None))
            using (var binary = File.Open(args[1], FileMode.Open, FileAccess.Read, FileShare.Read))
            using (var sha = SHA256.Create())
            using (var parent = Process.GetProcessById(parentId))
            {
                var hash = BitConverter.ToString(sha.ComputeHash(binary)).Replace("-", "").ToLowerInvariant();
                if (hash != args[2]) return 2;
                File.WriteAllText(args[3], "ready");
                if (!parent.WaitForExit(60000)) return 3;
                restart = true;
                using (var installer = Process.Start(new ProcessStartInfo(args[1], "/quiet /norestart") {
                    UseShellExecute = false
                }))
                {
                    installer.WaitForExit();
                    if (installer.ExitCode != 0 && installer.ExitCode != 3010) return 4;
                }
            }
            return 0;
        }
        catch (Exception) { return 5; }
        finally
        {
            if (restart && File.Exists(installed))
            {
                Process.Start(new ProcessStartInfo(installed) { UseShellExecute = true });
            }
        }
    }
}
