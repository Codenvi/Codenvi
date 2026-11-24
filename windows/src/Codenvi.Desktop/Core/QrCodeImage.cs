using QRCoder;

namespace Codenvi.Desktop.Core;

/// <summary>Renders a connection string as a QR code PNG (in memory only; never written to disk).</summary>
public static class QrCodeImage
{
    /// <summary>Black modules on white with a 4-module quiet zone, so it scans on the dark theme too.</summary>
    public static byte[] Png(string text, int pixelsPerModule = 8)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        return new PngByteQRCode(data).GetGraphic(pixelsPerModule);
    }
}
