package cc.controlReciclado;
// imports necesarios
import org.jcsp.lang.*;
import es.upm.aedlib.fifo.*;


public class ControlRecicladoCSP implements ControlReciclado, CSProcess {

	// constantes varias
	private enum Estado { LISTO, SUSTITUIBLE, SUSTITUYENDO }

	private final int MAX_P_CONTENEDOR; // a definir en el constructor
	private final int MAX_P_GRUA;       // a definir en el constructor

	// canales para comunicacion con el servidor y RPC
	// uno por operacion (peticiones aplazadas)
	private final Any2OneChannel chNotificarPeso;
	private final Any2OneChannel chIncrementarPeso;
	private final Any2OneChannel chNotificarSoltar;
	
	//Los siguientes cales conectan el contenedor al servidor
	private final One2OneChannel chPrepararSustitucion;
	private final One2OneChannel chNotificarSustitucion;

	// para aplazar peticiones de incrementarPeso
	private static class PetIncrementarPeso {
		public int p;
		public One2OneChannel chACK;

		PetIncrementarPeso (int p) {
			this.p = p;
			this.chACK = Channel.one2one();
		}
	}

	//Métodos auxliares
	private void compruebaPRE(int p){
		/* PRE puede fallas por 2 motivos */
		if (!(p>0 && p <= MAX_P_GRUA)) {
				throw new IllegalArgumentException("No se cumple CPRE");
			}
	  }

	public ControlRecicladoCSP(int max_p_contenedor,
			int max_p_grua) {

		// constantes del sistema
		MAX_P_CONTENEDOR = max_p_contenedor;
		MAX_P_GRUA       = max_p_grua;

		// creacion de los canales 
		chNotificarPeso = Channel.any2one();
		chIncrementarPeso = Channel.any2one();
		chNotificarSoltar = Channel.any2one();
		chPrepararSustitucion = Channel.one2one();
		chNotificarSustitucion = Channel.one2one();

		// arranque del servidor desde el constructor
		new ProcessManager(this).start();
	}

	public void notificarPeso(int p) throws IllegalArgumentException {
		// tratar PRE
		compruebaPRE(p);

		// AQUI SE CUMPLE PRE, enviar peticion
		chNotificarPeso.out().write(p);
		//Esperamos respuesta como parte de la sincronización
		//No es necesario guardar el dato que devuelve
		chNotificarPeso.in().read();

	}

	public void incrementarPeso(int p) throws IllegalArgumentException {
		// tratar PRE
		compruebaPRE(p);
		// AQUI SE CUMPLE PRE, creamos peticion para el servidor
		PetIncrementarPeso petition = new PetIncrementarPeso(p);
		// enviamos peticion
		chIncrementarPeso.out().write(petition);
		// esperamos confirmacion
		petition.chACK.in().read(); 
	}

	public void notificarSoltar() {
		// enviamos peticion
		chNotificarSoltar.out().write(null);
	}

	public void prepararSustitucion() {
		// enviamos peticion
		chPrepararSustitucion.out().write(null);
		// esperamos confirmacion
		chPrepararSustitucion.in().read();
	}

	public void notificarSustitucion() {
		// enviamos peticion
		chNotificarSustitucion.out().write(null);
		// No esperamos respuesta ya que esta solo será devuelta
		// Cuando se cumpla el CPRE de notificar Sustitucion
	}

	// SERVIDOR
	public void run() {
		
		int peso = 0;
		Estado e = Estado.LISTO;
		int accediendo = 0;
		boolean ok = true;
		
		// para recepcion alternativa condicional
		Guard[] entradas = {
				chNotificarPeso.in(),
				chIncrementarPeso.in(),
				chNotificarSoltar.in(),
				chPrepararSustitucion.in(),
				chNotificarSustitucion.in()
		};
		Alternative servicios =  new Alternative (entradas);
		// OJO ORDEN!!
		final int NOTIFICAR_PESO = 0;
		final int INCREMENTAR_PESO = 1;
		final int NOTIFICAR_SOLTAR = 2;
		final int PREPARAR_SUSTITUCION = 3;
		final int NOTIFICAR_SUSTITUCION = 4;
		// condiciones de recepcion
		final boolean[] sincCond = new boolean[5];
		
		//Estas 2 condiciones no necesitan comprobar el CPRE
		sincCond[NOTIFICAR_SOLTAR] = true; 
		sincCond[NOTIFICAR_SUSTITUCION] = true;

		// creamos coleccion para almacenar peticiones aplazadas
		FIFO<PetIncrementarPeso> listaPeticiones = new FIFOList<>();


		// bucle de servicio
		while (true) {
			// vars. auxiliares para comunicacion con clientes
			ok = true;

			// actualizacion de condiciones de recepcion
			if (e != Estado.SUSTITUYENDO) {
				sincCond[NOTIFICAR_PESO] = true;
				sincCond[INCREMENTAR_PESO] = true;
			}
			else {
				sincCond[NOTIFICAR_PESO] = false;
				sincCond[INCREMENTAR_PESO] = false;
			}

			if (e == Estado.SUSTITUIBLE && accediendo == 0) {
				sincCond[PREPARAR_SUSTITUCION] = true; 
			} 
			else {
				sincCond[PREPARAR_SUSTITUCION] = false; 
			}
			
			/* Usando el fairSelect podemos especificar
			 * los CPREs de los canales 
			 */
			switch (servicios.fairSelect(sincCond)) {
			case NOTIFICAR_PESO:
				// leemos peticion
				int peti = (int) chNotificarPeso.in().read();
				// procesar peticion
				int pesoActualizado = peti + peso;
				if (pesoActualizado > MAX_P_CONTENEDOR) {
					e = Estado.SUSTITUIBLE;
				}
				else {
					e = Estado.LISTO;
				}
				// terminamos de procesar la peticion
				chNotificarPeso.out().write(true);
				break;
			case INCREMENTAR_PESO:
				// leer peticion 
				PetIncrementarPeso peticion = (PetIncrementarPeso) chIncrementarPeso.in().read();
				int Peso_hipotetico = peticion.p + peso;
				// tratar peticion, y aplazar si no se cumple CPRE
				// o aplazar directamente
				if (peticion != null && Peso_hipotetico <= MAX_P_CONTENEDOR) {
					peso = Peso_hipotetico;
					accediendo++;
					peticion.chACK.out().write(true);
				}
				//aqui no se cumple, por tanto aplazamos
				else {
					listaPeticiones.enqueue(peticion);
				}
				break;
			case NOTIFICAR_SOLTAR:
				// leemos peticion
				chNotificarSoltar.in().read();
				// tratamos peticion
				accediendo--;
				break;
			case PREPARAR_SUSTITUCION:
				// leemos peticion
				chPrepararSustitucion.in().read();
				// tratamos peticion
				e = Estado.SUSTITUYENDO;
				chPrepararSustitucion.out().write(true);  
				ok = false;
				break;
			case NOTIFICAR_SUSTITUCION:
				// leemos peticion
				chNotificarSustitucion.in().read();  
				// tratar peticion
				peso = 0;
				e = Estado.LISTO;
				accediendo = 0;
				break;
			}
			// tratamiento de peticiones aplazadas
			while (ok) {      
				ok = false;            
				int size = listaPeticiones.size();               
				PetIncrementarPeso incPet;
				// cuando hay alguna peticion aplazada:
				while (size > 0) {
					incPet = listaPeticiones.first();                   
					int pesoAct = incPet.p + peso;
					// si hay espacio
					if (pesoAct <= MAX_P_CONTENEDOR) {
						ok = true;
						peso = pesoAct;
						accediendo++;
						listaPeticiones.dequeue(); 
						incPet.chACK.out().write(true);
					}
					// si no, siguiente
					else {
						listaPeticiones.dequeue();
						listaPeticiones.enqueue(incPet);
					}
					// decrementamos lista peticiones aplazadas ya que habremos tratado 1 mas
					size--;
				}
			}

		} 
	}
	
} 