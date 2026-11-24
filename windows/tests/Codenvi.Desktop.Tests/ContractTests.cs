using Codenvi.Contracts;
using Codenvi.Contracts.Crypto;
using Xunit;

namespace Codenvi.Desktop.Tests;

public class ContractTests
{
    private static readonly byte[] Key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();

    [Fact]
    public void Pair_string_roundtrip()
    {
        var cs = ConnectionString.Parse("codenvi://codenvi.example.com/pair?c=000G40R40M30E209185GR38E1W");
        Assert.Equal("codenvi.example.com", cs.Host);
        Assert.Equal("000G40R40M30E209185GR38E1W", cs.PairingCode);
        Assert.False(cs.CarriesKey);
        Assert.Equal(cs, ConnectionString.Parse(cs.ToString()) with { Key = null });
    }

    [Fact]
    public void Phone_string_carries_key()
    {
        var dev = Guid.NewGuid();
        var original = new ConnectionString { Host = "h.example:8443", PairingCode = "ABCD", DeviceId = dev, KeyVersion = 2, Key = Key, DeviceName = "Home PC" };
        var parsed = ConnectionString.Parse(original.ToString());
        Assert.Equal("h.example:8443", parsed.Host);
        Assert.Equal(dev, parsed.DeviceId);
        Assert.Equal(2, parsed.KeyVersion);
        Assert.Equal(Key, parsed.Key);
        Assert.Equal("Home PC", parsed.DeviceName);
        Assert.True(parsed.CarriesKey);
    }

    [Fact]
    public void Key_only_string()
    {
        var dev = Guid.NewGuid();
        var s = new ConnectionString { DeviceId = dev, KeyVersion = 1, Key = Key }.ToString();
        Assert.StartsWith("codenvi://key?", s);
        var parsed = ConnectionString.Parse(s);
        Assert.True(parsed.IsKeyOnly);
        Assert.Equal(Key, parsed.Key);
    }

    [Theory]
    [InlineData("android/1.4.0+42 (Android 14; Pixel 7)", "android", "1.4.0", 42, "Android 14", "Pixel 7")]
    [InlineData("desktop/1.4.0+57 (Windows 11 26100)", "desktop", "1.4.0", 57, "Windows 11 26100", null)]
    [InlineData("desktop/1.0.3+3", "desktop", "1.0.3", 3, null, null)]
    public void Client_header_parses(string header, string kind, string version, int build, string? os, string? model)
    {
        Assert.True(ClientInfo.TryParse(header, out var info));
        Assert.Equal(new ClientInfo(kind, version, build, os, model), info);
    }

}
