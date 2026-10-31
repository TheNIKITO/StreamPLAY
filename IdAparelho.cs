using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;

namespace StreamPLAY;

/// <summary>
/// Identificação forte do PC para o teste grátis (um teste por aparelho). Usa o UUID da placa-mãe (SMBIOS), que não
/// muda ao reinstalar o app nem ao formatar o Windows. Placas baratas às vezes vêm com um UUID genérico (zeros, FFFF...
/// ou 03000200-0400-0500-0006-000700080009): aí usa a identificação da instalação do Windows (MachineGuid), que só muda
/// formatando. O servidor recebe só o hash, nunca o número de verdade.
/// </summary>
static class IdAparelho
{
    static string _hash;

    public static string Hash => _hash ??= Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("streamplay-aparelho|" + Bruto())));

    static string Bruto()
    {
        try { if (UuidPlaca() is { } u) return "placa:" + u; } catch { }
        try
        {
            using var k = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64).OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            if (k?.GetValue("MachineGuid") is string g && g.Length > 8) return "windows:" + g.ToLowerInvariant();
        }
        catch { }
        return "app:" + Store.MeuAparelho();   // último recurso: o código desta instalação do app
    }

    static readonly string[] Genericos = { "00000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff", "03000200-0400-0500-0006-000700080009" };

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern uint GetSystemFirmwareTable(uint provider, uint tableId, byte[] buffer, uint size);

    // Tabela SMBIOS "RSMB": cabeçalho de 8 bytes e as estruturas; a de tipo 1 (System Information) tem o UUID no byte 8.
    static string UuidPlaca()
    {
        const uint RSMB = 0x52534D42;
        uint n = GetSystemFirmwareTable(RSMB, 0, null, 0);
        if (n == 0) return null;
        var b = new byte[n];
        if (GetSystemFirmwareTable(RSMB, 0, b, n) != n) return null;
        int fim = 8 + BitConverter.ToInt32(b, 4), i = 8;
        while (i + 4 <= fim && i + 4 <= b.Length)
        {
            byte tipo = b[i], tam = b[i + 1];
            if (tam < 4) return null;
            if (tipo == 1 && tam >= 0x19 && i + 24 <= b.Length)
            {
                // os 3 primeiros campos ficam em little-endian (como o Windows mostra no "wmic csproduct get uuid")
                var u = new Guid(b.AsSpan(i + 8, 16)).ToString();
                return Genericos.Contains(u) ? null : u;
            }
            if (tipo == 127) return null;   // fim da tabela
            // pula a parte formatada e as strings (terminam com dois zeros seguidos)
            int j = i + tam;
            while (j + 1 < b.Length && !(b[j] == 0 && b[j + 1] == 0)) j++;
            i = j + 2;
        }
        return null;
    }
}
