using System.Runtime.InteropServices;

namespace StreamPLAY;

/// <summary>
/// Criptografia do Windows (DPAPI) presa ao usuário deste PC: o que for gravado assim só abre nesta conta,
/// neste computador. Usada na cópia da lista salva em disco, para ninguém abrir o arquivo e ler o conteúdo.
/// </summary>
static class Protegido
{
    static readonly byte[] Entropia = "StreamPLAY|lista|v1"u8.ToArray();

    public static byte[] Cifrar(byte[] dados) => Rodar(dados, cifrar: true) ?? throw new IOException("Não consegui criptografar a lista.");

    /// <summary>Devolve null se não der para abrir (arquivo de outra conta/PC ou corrompido).</summary>
    public static byte[] Abrir(byte[] dados) => Rodar(dados, cifrar: false);

    [StructLayout(LayoutKind.Sequential)]
    struct Blob { public int Tamanho; public IntPtr Dados; }

    const int SemJanela = 1;   // CRYPTPROTECT_UI_FORBIDDEN

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool CryptProtectData(ref Blob entrada, string descricao, ref Blob entropia, IntPtr reservado, IntPtr prompt, int flags, out Blob saida);

    [DllImport("crypt32.dll", SetLastError = true)]
    static extern bool CryptUnprotectData(ref Blob entrada, IntPtr descricao, ref Blob entropia, IntPtr reservado, IntPtr prompt, int flags, out Blob saida);

    [DllImport("kernel32.dll")]
    static extern IntPtr LocalFree(IntPtr p);

    static byte[] Rodar(byte[] dados, bool cifrar)
    {
        var hDados = GCHandle.Alloc(dados, GCHandleType.Pinned);
        var hEnt = GCHandle.Alloc(Entropia, GCHandleType.Pinned);
        try
        {
            var entrada = new Blob { Tamanho = dados.Length, Dados = hDados.AddrOfPinnedObject() };
            var entropia = new Blob { Tamanho = Entropia.Length, Dados = hEnt.AddrOfPinnedObject() };
            bool ok = cifrar
                ? CryptProtectData(ref entrada, "StreamPLAY", ref entropia, IntPtr.Zero, IntPtr.Zero, SemJanela, out var saida)
                : CryptUnprotectData(ref entrada, IntPtr.Zero, ref entropia, IntPtr.Zero, IntPtr.Zero, SemJanela, out saida);
            if (!ok) return null;
            try
            {
                var r = new byte[saida.Tamanho];
                Marshal.Copy(saida.Dados, r, 0, saida.Tamanho);
                return r;
            }
            finally { LocalFree(saida.Dados); }
        }
        finally { hDados.Free(); hEnt.Free(); }
    }
}
