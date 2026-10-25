namespace StreamPLAY;

/// <summary>Dá acesso ao vlc.zip guardado dentro desta dll.</summary>
public static class VlcPacote
{
    public static Stream Abrir() => typeof(VlcPacote).Assembly.GetManifestResourceStream("vlc.zip");
}
