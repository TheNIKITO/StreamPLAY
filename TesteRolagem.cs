using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace StreamPLAY;

/// <summary>
/// Só para testes (STREAMPLAY_TESTE_ROLAGEM=arquivo): rola a página sozinho como a roda do mouse, mede quanto
/// cada quadro levou para aparecer, grava o resultado no arquivo e fecha. Não mexe no mouse de verdade.
/// </summary>
static class TesteRolagem
{

    public static void Talvez(MainWindow w)
    {
        var arq = Environment.GetEnvironmentVariable("STREAMPLAY_TESTE_ROLAGEM");
        if (string.IsNullOrEmpty(arq)) return;
        var espera = new DispatcherTimer { Interval = TimeSpan.FromSeconds(4) };   // deixa a tela assentar
        espera.Tick += (_, _) => { espera.Stop(); Rodar(w, arq); };
        espera.Start();
    }

    static void Rodar(MainWindow w, string arq)
    {
        var sv = UI.Find<ScrollViewer>(w.PageHost.Content as DependencyObject);
        if (sv == null) { File.WriteAllText(arq, "sem ScrollViewer"); w.Close(); return; }

        var quadros = new List<double>();
        TimeSpan? anterior = null;
        EventHandler aoDesenhar = (_, e) =>
        {
            var agora = ((RenderingEventArgs)e).RenderingTime;
            if (anterior is { } a && agora != a) quadros.Add((agora - a).TotalMilliseconds);
            anterior = agora;
        };
        CompositionTarget.Rendering += aoDesenhar;

        // 60 giros a cada 110 ms (quem rola a lista rápido): 40 para baixo e 20 para cima
        int giro = 0;
        var roda = new DispatcherTimer(DispatcherPriority.Input) { Interval = TimeSpan.FromMilliseconds(110) };
        roda.Tick += (_, _) =>
        {
            if (giro < 60) { Smooth.Rolar(sv, giro < 40 ? -120 : 120); giro++; return; }
            if (giro++ < 64) return;   // espera a última animação terminar
            roda.Stop();
            CompositionTarget.Rendering -= aoDesenhar;
            var q = quadros.OrderBy(x => x).ToList();
            double P(double p) => q.Count == 0 ? 0 : q[Math.Min(q.Count - 1, (int)(p * q.Count))];
            var inv = CultureInfo.InvariantCulture;
            File.WriteAllText(arq, string.Format(inv,
                "quadros={0} media={1:0.0}ms p95={2:0.0}ms p99={3:0.0}ms max={4:0.0}ms atrasados(>25ms)={5} travadas(>50ms)={6} rolou_ate={7:0}",
                q.Count, q.Count == 0 ? 0 : q.Average(), P(0.95), P(0.99), q.Count == 0 ? 0 : q[^1],
                q.Count(x => x > 25), q.Count(x => x > 50), sv.ScrollableHeight));
            w.Close();
        };
        roda.Start();
    }
}
