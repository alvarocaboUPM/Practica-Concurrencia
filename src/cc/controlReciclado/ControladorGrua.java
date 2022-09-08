

import es.upm.babel.cclib.ConcIO;

public class ControladorGrua extends Thread {
  private final int indice;
  private final ApiGruas gruas;
  private final ControlReciclado cr;

  public ControladorGrua (int indice,
                          ApiGruas gruas,
                          ControlReciclado cr) {
    this.indice = indice;
    this.gruas = gruas;
    this.cr = cr;
  }

  public void run () {
    while (true) {
      int peso;

      ConcIO.printfnl("Grua %d inicio recoger", indice);
      peso = gruas.recoger(indice);
      ConcIO.printfnl("Grua %d recogió %d", indice, peso);

      cr.notificarPeso(peso);

      cr.incrementarPeso(peso);

      ConcIO.printfnl("Grua %d inicia soltar", indice);
      gruas.soltar(indice);
      ConcIO.printfnl("Grua %d soltó %d", indice, peso);

      cr.notificarSoltar();
    }
  }
}
