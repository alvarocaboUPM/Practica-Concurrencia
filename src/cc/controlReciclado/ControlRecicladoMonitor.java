package cc.controlReciclado;
import es.upm.babel.cclib.Monitor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;

import es.upm.babel.cclib.ConcIO;

/* Práctica de control de planta de reciclado con monitores
 *    Álvaro Cabo Ciudad & Pablo Fernández de Gracia
 * 
 * Al final del código se puede encontrar el control de versiones que 
 * hemos llevado a cabo hasta esta "version final".
 * 
 * Para debugear el código puede descomentar las líneas que utilizan
 * ConcIO y al ejecutar podrá saber qué método se está ejecutando
 * 
 */
@SuppressWarnings("unused")
public final class ControlRecicladoMonitor implements ControlReciclado {
  /*  Variables de inicio de la planta */
  private final int MAX_P_CONTENEDOR;
  private final int MAX_P_GRUA;

  private enum Estado { 
    LISTO, //Contenedor admite + carga
    SUSTITUIBLE, //al menos una de las grúas lleva más carga de la que cabe en el contenedor,
    //por lo que dicha grúa solicita un contenedor nuevo
    SUSTITUYENDO //no se debe depositar carga ya que el contenedor puede estar siendo reemplazado
  }

  //Variables globales (Recursos compartidos) [DOM]
  private int peso;
  private Estado e;
  private int accediendo; //Cuantas gruas están intentando soltar peso

                       /*  Elementos de sincronización */

  //Monitor
  private Monitor mutex;
  
  /*  
        Determinamos las condiciones de bloqueo 
  1) Contenedor
    La "sección crítica" del proceso es sustituir el
    contenedor, teniendo en cuenta que el contenedor es
    un recurso compartido por las grúas y que, como se describe
    en la guía, es un proceso lento, es necesario protegerlo.
  */

  private Monitor.Cond condPrepararSustitucion;   /*

  2) Gruas
    Siguiendo la traza del Controlador de la Grua
    - Primero se recoge la chatarra -> peso
    y es necesario que antes de soltarla en el contenedor:
        - NotificarPeso(p)
        - IncrementarPeso(p)
    
      En el caso de notificarPeso(), solo necesitamos una
      condition porque el CPRE solo nos hace comprobar el estado */

  private Monitor.Cond condNotificarPeso;
 
  /*  En cambio para IncrementarPeso(), su CPRE depende del
  estado, como al notificarPeso(), pero además depende del peso 
  que lleve cada grúa, por lo que será necesario una condition
  para cada grúa en funcionamiento */

  /*Objeto para crear conditions para bloquear una grúa*/

  private class BloquearGruas {
		private Monitor.Cond condition;
		private int peso;

    //Constructor
		public BloquearGruas(int peso, Monitor m) {
			this.peso = peso;
			condition = m.newCond();
		}
    public void on(){
      condition.signal();
    }
    public void off(){
      condition.await();
    }
    public int getPeso(){
      return peso;
    }
	}

   /* Para implementar Incrementar se va a usar una cola de peticiones
  que permita saber el orden de desbloqueo de las peticiones
  */
  private Collection<BloquearGruas> condIncrementarPeso;

  public ControlRecicladoMonitor (int max_p_contenedor,
                                  int max_p_grua) {
    MAX_P_CONTENEDOR = max_p_contenedor;
    MAX_P_GRUA = max_p_grua;

    /* Inicializamos las variables globales */

    //Inicial
    peso=0;
    e=Estado.LISTO;
    accediendo=0;

    //Inicio de monitores
    mutex = new Monitor();

    // Iniciar las condiciones y la cola de peticiones de bloqueo

		condNotificarPeso = mutex.newCond();

    condIncrementarPeso = new ArrayList<BloquearGruas>();
		
		condPrepararSustitucion = mutex.newCond();
  }

  /* MÉTODOS DE LA INTERFAZ */

  public void notificarPeso(int p) {
    //Comprobamos las precondiciones
    compruebaPRE(p);
    //Entramos en sc
    mutex.enter();
      //CPRE: self.estado != sustituyendo;
      if (e == Estado.SUSTITUYENDO) {
        // Bloquear el thread Grúa
        condNotificarPeso.await();	
        ConcIO.printfnl("BLOCK -> " + new Throwable().getStackTrace()[0].getMethodName());
      }

      //Ahora podemos pasar a la ejecución

      /* Creamos una variable local para
      anotar cómo quedaría el peso del contenedor
      en caso de añadir carga con un peso p */
      int Peso_hipotetico = peso + p;

      //POST: 
        //selfpre.peso + p [Peso_Hipotético] > MAX_P_CONTENEDOR => self.estado = sustituible
        if(Peso_hipotetico > MAX_P_CONTENEDOR){
          e = Estado.SUSTITUIBLE;
        }
        //selfpre.peso + p ≤ MAX_P_CONTENEDOR ⇒ self.estado = listo
        else{
          e = Estado.LISTO;
        }
      //Liberamos los threads
      liberar();
    // Salimos de sc
    mutex.leave();
  }


  public void incrementarPeso(int p) {

    /* En el caso de este método, su ejecución depende de
    el objeto grua que lo esté ejecutando, por lo que puede
    haber varios threads grua intentando incrementar el peso.
    Para evitar una CC se utiliza una clase BloquearGrua que
    permite bloquear la grua y guardar el peso que quiere meter
    en el contenedor hasta que sea posible hacerlo.
    */

    //Comprobamos las precondiciones
    compruebaPRE(p);
        //Entramos en sc
        mutex.enter();
          //Comprobamos CPRE, en caso contrario await()++
          // 1. self.peso + p ≤MAX_P_CONTENEDOR
          // 2. self.estado != sustituyendo
          if(e == Estado.SUSTITUYENDO || (peso + p) > MAX_P_CONTENEDOR){
            //Si no se cumple el CPRE, bloqueamos la grua que está ejecutando el thread actual
            	// Crear una petición de bloqueo con el peso que queremos meter
		          BloquearGruas bloqueo = new BloquearGruas(p, mutex);
              // La metemos en la cola de peticiones
		          condIncrementarPeso.add(bloqueo);
              // Bloquear el thread Grua en la condition de la petition creada
		          bloqueo.off();
              ConcIO.printfnl("BLOCK -> " + new Throwable().getStackTrace()[0].getMethodName());      
          }
          //Podemos ejecutar
          // selfpre =(peso, e, a) AND self = (peso + p, e, a + 1)
          peso= peso +p;
          //e = e
          accediendo++; 

        //Liberamos los threads
        liberar();
      // Salimos de sc
      mutex.leave();
  }

  public void notificarSoltar() {
        //Entramos en sc
        mutex.enter();
        //No hay PRE ni CPRE
          //Ejecución
          //selfpre =(p, e, a) ANDself = (p, e, a −1)
          accediendo--;
        //Liberamos los threads
        liberar();
      // Salimos de sc
      mutex.leave();
  }

  public void prepararSustitucion() {
    //Cambia el estado a SUSTITUYENDO de manera segura
    mutex.enter();
    //CPRE: self =(_, sustituible , 0)
      if(e!=Estado.SUSTITUIBLE || accediendo>0 ){
        // Bloquear el thread Grúa
        condPrepararSustitucion.await();	 
        // Println para debuguear el código y encontrar errores
         ConcIO.printfnl("BLOCK -> " + new Throwable().getStackTrace()[0].getMethodName());
         }
      
      //: self =(_, sustituyendo, 0)
        e=Estado.SUSTITUYENDO;
        //Accediendo ya tenía que ser 0 para entrar aquí
      //Liberamos los threads
      //liberar();
    mutex.leave();
  }

  public void notificarSustitucion() {
    //Se encarga de "reiniciar" el proceso al estado inicial
    mutex.enter();
     //No hay PRE ni CPRE
    //Ejecución 
      peso=0;
      e=Estado.LISTO;
      accediendo=0;

    liberar();
    mutex.leave();
  }

      /*  MÉTODOS AUXILIARES

      Estos métodos permiten reciclar el código
      que deben ejecutar todas las funciones.

       - Comprobar el PRE (Notificar e incrementar)
       - Liberar los threads bloqueados al final de su ejecución

      */

  private void compruebaPRE(int p){
    /* PRE puede fallas por 2 motivos */
    if (!(p>0 && p <= MAX_P_GRUA)) {
			throw new IllegalArgumentException("No se cumple CPRE");
		}
  }
  

  private void liberar(){
    
    /* Este método lo vamos a implementar para que al final de cada acceso
     * a una sección crítica se liberen todos los procesos que cumplan su CPRE
     * Para ello, evaluamos en 1er lugar si tiene threads parados y después
     * si cumplen su precondición.
     * 
     * Corrección v1.2
     *  El método está implementado con elif porque no podemos liberar todos
     *  los procesos a la vez dado que el uso de monitores perdería su sentido.
     * */
  
    //1. Notificar peso
		  // CPRE: self.estado != sustituyendo
		if (e!= Estado.SUSTITUYENDO && condNotificarPeso.waiting() > 0 ){
			//Si cumplen las 2 condiciones, liberamos su proceso
			condNotificarPeso.signal();
      ConcIO.printfnl("DESBLOQUEA -> NotificarPeso");
    }

    //2. Preparar sustitución
		  // CPRE: estado == sustituible y no hay gruas accediendo al contenedor
		else if(e == Estado.SUSTITUIBLE && accediendo==0 && condPrepararSustitucion.waiting()>0){
		  //Si cumplen las 2 condiciones, liberamos su proceso
			condPrepararSustitucion.signal();
      ConcIO.printfnl("DESBLOQUEA -> PrepararSustitución");
    }
		
    //3. Incrementar peso
		// CPRE: self.peso  + self.p  <= MAX_P_CONTENEDOR AND self.estado != sustituyendo

    /* En este caso es necesario implementar un bucle que itere por todas las 
     * peticiones de bloqueo y las 
     */
		else {
      // Inicializar una petición de tipo PetitionGruas
      BloquearGruas petitionGrua = null;
    
      for (BloquearGruas grua : condIncrementarPeso) {
        // calcular el nuevo peso que tendía el contenedor
        int Peso_hipotetico = peso + grua.peso;
        ConcIO.printfnl("GRUA con peso: "+grua.getPeso());
        if (Peso_hipotetico <= MAX_P_CONTENEDOR && e != Estado.SUSTITUYENDO) {
          //Guardamos la grúa que queremos desbloquear
          petitionGrua=grua;
          //ConcIO.printfnl("DESBLOQUEA -> IncrementarPeso la grúa con peso: "+ petitionGrua.getPeso());
          //y paramos el bucle para sacarla de forma segura
          break;
        }
      }
      if(petitionGrua !=null){
            // Sacamos a la grua de la lista de bloqueadas
            
            condIncrementarPeso.remove(petitionGrua);
            // Desbloqueamos su proceso
            petitionGrua.on();
            ConcIO.printfnl("Liberada grua con peso: "+petitionGrua.getPeso());
          }        
      }
		}
	}

/* CONTROL DE VERSIONES:
 * 6/6-> Solo recibimos fallos en notificarSustitucion() ya que hay
 * grúas que se llaman que cumplen los requisitos y deberían haber
 * sido desbloqueadas pero no lo están.
 * SOLUCIÓN: Por error, estábamos desbloqueandon los threads que cumplían
 * el CPRE de prepararSustitución, lo que significa que todos los threads
 * que desbloqueemos no cumplirán el CPRE de ningún otro método, ya que
 * todos exigen que Estado != Sustituyendo, por lo que los que desbloqueábamos
 * aquí volvían a ser bloqueados al llamar a otros métodos, por ejemplo notificarPeso
 * 
 * 7/6 - El otro problema que tenía la implementación
 * // Bloquear el thread Grua en la condition de la petition creada
		          bloqueo.condition.await();
 *  // La metemos en la cola de peticiones
		          condIncrementarPeso.add(bloqueo);
    Se bloqueaban threads antes de meterlos en la cola de bloqueo

	11/06 Versión final: Se ha corregido el error que causaba los fallos de desbloqueo
	y bloqueo: Se guardaba el peso + p en una variable llamada peso_hipotético, 
	esa variable era local por lo que era compartida por el método, y todos las grúas
	lo modificaban, provocando condiciones de carrera a la hora de cumplir el POST
	del método.
	- Además se ha implementado el método compruebaPRE(int p) para comprobar
  que se cumplen las precondiciones y reciclar código en el camino
  - Por último se ha prescindido del método bloquear(int p) ya que solo se 
  ejecuta en una instancia y no creemos que es necesario
 */
