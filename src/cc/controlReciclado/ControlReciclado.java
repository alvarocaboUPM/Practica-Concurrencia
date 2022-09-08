

public interface ControlReciclado {
  public void notificarPeso(int p) throws IllegalArgumentException;
  public void incrementarPeso(int p) throws IllegalArgumentException;
  public void notificarSoltar();
  public void prepararSustitucion();
  public void notificarSustitucion();
}
