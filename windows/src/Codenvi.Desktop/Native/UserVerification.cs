using System.Runtime.InteropServices;
using System.Text;
using Windows.Security.Credentials.UI;

namespace Codenvi.Desktop.Native;

/// <summary>
/// Gate for "Show encryption key" (docs/design.md §5.1): Windows Hello if it is set up, otherwise the
/// Windows password of the current user (CredUI + LogonUser).
/// </summary>
internal static partial class UserVerification
{
    public static async Task<bool> VerifyAsync(IntPtr owner, string message)
    {
        try
        {
            var availability = await UserConsentVerifier.CheckAvailabilityAsync();
            if (availability == UserConsentVerifierAvailability.Available)
            {
                var result = await UserConsentVerifier.RequestVerificationAsync(message);
                return result == UserConsentVerificationResult.Verified;
            }
        }
        catch (Exception) { /* fall back to password */ }
        return VerifyPassword(owner, message);
    }

    // ---------------- Password fallback ----------------
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDUI_INFO
    {
        public int cbSize;
        public IntPtr hwndParent;
        public string pszMessageText;
        public string pszCaptionText;
        public IntPtr hbmBanner;
    }

    private const int CREDUIWIN_GENERIC = 0x1;
    private const int CREDUIWIN_ENUMERATE_CURRENT_USER = 0x200;
    private const int LOGON32_LOGON_INTERACTIVE = 2;
    private const int LOGON32_PROVIDER_DEFAULT = 0;

    [DllImport("credui.dll", CharSet = CharSet.Unicode)]
    private static extern int CredUIPromptForWindowsCredentials(ref CREDUI_INFO info, int authError, ref uint authPackage,
        IntPtr inAuthBuffer, uint inAuthBufferSize, out IntPtr outAuthBuffer, out uint outAuthBufferSize,
        ref bool save, int flags);

    [DllImport("credui.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredUnPackAuthenticationBuffer(int flags, IntPtr authBuffer, uint authBufferSize,
        StringBuilder userName, ref int maxUserName, StringBuilder domainName, ref int maxDomainName,
        StringBuilder password, ref int maxPassword);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LogonUser(string user, string? domain, string password, int logonType, int provider, out IntPtr token);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("ole32.dll")]
    private static extern void CoTaskMemFree(IntPtr ptr);

    private static bool VerifyPassword(IntPtr owner, string message)
    {
        var info = new CREDUI_INFO
        {
            cbSize = Marshal.SizeOf<CREDUI_INFO>(),
            hwndParent = owner,
            pszCaptionText = "Codenvi",
            pszMessageText = message,
        };
        uint package = 0;
        var save = false;
        var rc = CredUIPromptForWindowsCredentials(ref info, 0, ref package, IntPtr.Zero, 0, out var outBuf, out var outSize,
            ref save, CREDUIWIN_GENERIC | CREDUIWIN_ENUMERATE_CURRENT_USER);
        if (rc != 0) return false;
        try
        {
            int u = 256, d = 256, p = 256;
            var user = new StringBuilder(u);
            var domain = new StringBuilder(d);
            var pass = new StringBuilder(p);
            if (!CredUnPackAuthenticationBuffer(0, outBuf, outSize, user, ref u, domain, ref d, pass, ref p)) return false;

            var name = user.ToString();
            string? dom = domain.Length > 0 ? domain.ToString() : null;
            var slash = name.IndexOf('\\');
            if (dom is null && slash > 0) { dom = name[..slash]; name = name[(slash + 1)..]; }
            if (!string.Equals(name, Environment.UserName, StringComparison.OrdinalIgnoreCase) && !name.Contains('@')) return false;

            var ok = LogonUser(name, dom ?? ".", pass.ToString(), LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT, out var token);
            pass.Clear();
            if (ok) CloseHandle(token);
            return ok;
        }
        finally
        {
            if (outBuf != IntPtr.Zero) CoTaskMemFree(outBuf);
        }
    }
}
