export type Rol = 'ADMIN' | 'TECNICO' | 'USER';
export type Estado = 'PENDIENTE' | 'EN_CURSO' | 'FINALIZADA' | 'CANCELADA';
export type Prioridad = 'BAJA' | 'MEDIA' | 'ALTA' | 'CRITICA';
export type Impacto = 'INDIVIDUAL' | 'AREA' | 'SEDE';

export interface Usuario {
  id: string; email: string; nombre: string; cargo: string; area?: string;
  sedeId?: number; rol: Rol; activo: boolean;
}

export interface TokenResponse { accessToken: string; expiresIn: number; usuario: Usuario; }

export interface Item { id: number; codigo: string; nombre: string; }
export interface EquipoItem { id: number; codigo: string; tipo: string; marca?: string; modelo?: string; sedeId: number; }
export interface TipoItem { id: number; codigo: string; nombre: string; categoriaId: number; categoriaCodigo: string; }
export interface EspItem { id: number; codigo: string; nombre: string; categoriaId: number; tipoFallaId?: number; }
export interface Catalogo {
  sedes: Item[]; equipos: EquipoItem[]; categorias: Item[]; tiposFalla: TipoItem[];
  especialidades: EspItem[]; prioridades: Item[]; estados: Item[];
}

export interface TicketResumen {
  id: string; numero: string; descripcion: string; estado: Estado; prioridad: Prioridad;
  categoria?: string; tipoFalla?: string; sede: string; equipo?: string; tecnicoId?: string;
  tecnicoNombre?: string; solicitanteNombre?: string; requiereRevision: boolean; aiConfianza?: number;
  canal: string; esDemo: boolean; fechaCreacion: string; fechaFinalizacion?: string;
}

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number; }

export interface Diagnostico {
  id: string; origen: 'TEXTO' | 'IMAGEN'; adjuntoId?: string; proveedor: string; modelo: string;
  tipoEquipo?: string; categoriaCodigo?: string; tipoFallaCodigo?: string; tipoFallaNombre?: string;
  descripcion?: string; causaProbable?: string; confianza: number; concluyente: boolean;
  evidencias: string[]; recomendacion?: string; especialidadSugerida?: string; estado: string; fecha: string;
}

export interface Permisos {
  iniciar: boolean; finalizar: boolean; cancelar: boolean; reabrir: boolean; registrarActividad: boolean;
  reclasificar: boolean; cambiarPrioridad: boolean; asignar: boolean; solicitarReasignacion: boolean;
  adjuntar: boolean; calificar: boolean;
}

export interface TicketDetalle {
  resumen: TicketResumen; descripcionCompleta: string; impacto: Impacto; bloqueaTrabajo: boolean;
  cargoSolicitante: string; correoSolicitante?: string; categoriaCodigo?: string; tipoFallaCodigo?: string;
  solucion?: string; reaperturas: number; fechaAsignacion?: string; fechaInicioAtencion?: string;
  recomendacion?: any;
  historial: { tipo: string; valorAnterior?: string; valorNuevo?: string; detalle?: string; usuario: string; fecha: string }[];
  adjuntos: { id: string; nombre: string; mime: string; tamano: number; fecha: string }[];
  diagnosticos: Diagnostico[];
  asignaciones: { id: string; modo: string; tecnicoRecomendado?: string; tecnicoAsignado: string; puntajeRecomendado?: number;
    puntajeAsignado?: number; motivo: string; explicacion?: any; activa: boolean; fecha: string }[];
  actividades: { id: string; tecnico: string; tipo: string; descripcion: string; minutos: number; fecha: string }[];
  encuesta?: { calificacion: number; comentario?: string; fecha: string };
  permisos: Permisos;
}

export interface Breakdown {
  id: string; nombre: string; e: number; x: number; c: number; d: number; dSinHistorial: boolean;
  carga: number; cargaMaxima: number; puntaje: number; explicacion: string;
}
export interface Recomendaciones {
  ranking: Breakdown[]; excluidos: { id: string; nombre: string; motivo: string }[];
  decision: 'AUTO' | 'REVISION'; motivo: string;
  pesos: { especialidad: number; experiencia: number; carga: number; desempeno: number; version: string };
}

export interface Tecnico {
  id: string; nombre: string; email: string; cargo: string; sedeId?: number; nivel: string; disponible: boolean;
  activo: boolean; cargaMaxima: number; carga: number; abiertos: number; especialidades: string[];
  especialidadIds: number[]; resueltos: number;
}

export interface Notificacion { id: string; titulo: string; mensaje: string; ticketId?: string; leida: boolean; fecha: string; }

export interface AgentReply {
  sesion_id: string; respuesta: string; etapa: string; borrador?: Borrador | null;
  ticket?: { id: string; numero: string; estado: string } | null; sugerencias: string[];
  audio_wav_base64?: string | null; transcripcion?: string;
}
export interface Borrador {
  id: string; descripcion: string; sedeId?: number; equipoId?: number | null; equipo?: string | null;
  impacto: Impacto; bloqueaTrabajo: boolean; categoriaSugerida?: string | null; tipoFallaSugerido?: string | null;
  prioridadEstimada: Prioridad;
}
