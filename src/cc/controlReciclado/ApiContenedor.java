

import es.upm.babel.cclib.Semaphore;
import es.upm.babel.cclib.ConcIO;
import java.util.Random;

public class ApiContenedor {
  // Peso límite en el contendor
  public final int MAX_P_CONTENEDOR;

  // El generador números aleatorios, peso, preparado y atascado van a
  // ser usados simultáneamente
  private Semaphore mutex = new Semaphore(1);

  // Generador de números aleatorios
  private Random random = new Random(0);

  // Peso en el contenedor
  private int peso = 0;

  // El contendor está preparado
  private boolean preparado = true;

  // Se ha soltado una carga son contenedor
  private boolean atascado = false;

  // Crea el contenedor
  public ApiContenedor(int max_p_contenedor) {
    MAX_P_CONTENEDOR = max_p_contenedor;
  }

  // Sustituye el contenedor actual con otro vacio
  public void sustituir() {
    if (peso > MAX_P_CONTENEDOR) {
      while (true) {
        ConcIO.printfnl ("ERROR: el contenedor no se puede mover, peso " + peso);
        try {
          Thread.sleep(1000);
        } catch (InterruptedException x) {}
      }
    }
    // Sólo simulamos un retardo constante
    preparado = false;
    try {
      ConcIO.printfnl ("Retirando contenedor con peso " + peso);
      mutex.await();
      int t = random.nextInt(peso / 10);
      peso = 0;
      mutex.signal();
      Thread.sleep(t);
    } catch (InterruptedException x) {}
    if (atascado) {
      while (true) {
        ConcIO.printfnl ("ERROR: contenedor atascado por chatarra en carril.");
        try {
          Thread.sleep(1000);
        } catch (InterruptedException x) {}
      }

    }
    preparado = true;
  }

  // Incrementar el peso real en el contenedor
  public void incrementar(int p) {
    boolean sobrepeso;
    mutex.await();
    peso += p;
    sobrepeso = peso > MAX_P_CONTENEDOR;
    mutex.signal();
    if (!preparado) {
      atascado = true;
    }
    if (sobrepeso) {
      ConcIO.printfnl ("PESO LÍMITE SOBREPASADO: ¡" + peso + "!");
    }
  }
}
