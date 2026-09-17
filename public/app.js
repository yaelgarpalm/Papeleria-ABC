const productoForm = document.getElementById('productoForm');
const tablaProductos = document.getElementById('tablaProductos');
const mensajeProducto = document.getElementById('mensajeProducto');
const tituloFormulario = document.getElementById('tituloFormulario');
const btnGuardar = document.getElementById('btnGuardar');
const btnCancelar = document.getElementById('btnCancelar');
const btnLogout = document.getElementById('btnLogout');
const btnActualizar = document.getElementById('btnActualizar');
const usuarioActual = document.getElementById('usuarioActual');
const sinProductos = document.getElementById('sinProductos');

async function comprobarSesion() {
    try {
        const respuesta = await fetch('/api/sesion');
        const datos = await respuesta.json();

        if (!datos.autenticado) {
            window.location.href = '/';
            return false;
        }

        usuarioActual.textContent =
            `${datos.usuario.nombre} (${datos.usuario.rol})`;

        return true;

    } catch (error) {
        console.error(error);
        window.location.href = '/';
        return false;
    }
}

async function cargarProductos() {
    try {
        const respuesta = await fetch('/api/productos');
        const datos = await respuesta.json();

        if (!respuesta.ok) {
            if (respuesta.status === 401) {
                window.location.href = '/';
                return;
            }

            throw new Error(datos.error || 'Error al cargar productos.');
        }

        tablaProductos.innerHTML = '';

        if (datos.length === 0) {
            sinProductos.classList.remove('hidden');
            return;
        }

        sinProductos.classList.add('hidden');

        datos.forEach(producto => {
            const fila = document.createElement('tr');

            fila.innerHTML = `
                <td>${producto.id}</td>
                <td>${escapeHtml(producto.nombre)}</td>
                <td>${escapeHtml(producto.categoria)}</td>
                <td>$${Number(producto.precio).toFixed(2)}</td>
                <td>${producto.stock}</td>
                <td class="actions">
                    <button
                        class="btn-small btn-edit"
                        onclick="editarProducto(${producto.id})">
                        Editar
                    </button>

                    <button
                        class="btn-small btn-delete"
                        onclick="eliminarProducto(${producto.id})">
                        Eliminar
                    </button>
                </td>
            `;

            tablaProductos.appendChild(fila);
        });

    } catch (error) {
        console.error(error);

        mostrarMensaje(
            error.message,
            true
        );
    }
}

productoForm.addEventListener('submit', async (event) => {
    event.preventDefault();

    const id = document.getElementById('productoId').value;

    const producto = {
        nombre: document.getElementById('nombre').value,
        categoria: document.getElementById('categoria').value,
        precio: document.getElementById('precio').value,
        stock: document.getElementById('stock').value
    };

    const metodo = id ? 'PUT' : 'POST';
    const url = id
        ? `/api/productos/${id}`
        : '/api/productos';

    try {
        const respuesta = await fetch(url, {
            method: metodo,
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(producto)
        });

        const datos = await respuesta.json();

        if (!respuesta.ok) {
            throw new Error(datos.error || 'No se pudo guardar el producto.');
        }

        mostrarMensaje(
            id
                ? 'Producto actualizado correctamente.'
                : 'Producto agregado correctamente.'
        );

        limpiarFormulario();
        await cargarProductos();

    } catch (error) {
        console.error(error);
        mostrarMensaje(error.message, true);
    }
});

async function editarProducto(id) {
    try {
        const respuesta = await fetch(`/api/productos/${id}`);
        const producto = await respuesta.json();

        if (!respuesta.ok) {
            throw new Error(producto.error || 'No se pudo obtener el producto.');
        }

        document.getElementById('productoId').value = producto.id;
        document.getElementById('nombre').value = producto.nombre;
        document.getElementById('categoria').value = producto.categoria;
        document.getElementById('precio').value = producto.precio;
        document.getElementById('stock').value = producto.stock;

        tituloFormulario.textContent = 'Editar producto';
        btnGuardar.textContent = 'Actualizar producto';
        btnCancelar.classList.remove('hidden');

        window.scrollTo({
            top: 0,
            behavior: 'smooth'
        });

    } catch (error) {
        console.error(error);
        mostrarMensaje(error.message, true);
    }
}

async function eliminarProducto(id) {
    const confirmar = confirm(
        '¿Deseas eliminar este producto?'
    );

    if (!confirmar) {
        return;
    }

    try {
        const respuesta = await fetch(`/api/productos/${id}`, {
            method: 'DELETE'
        });

        const datos = await respuesta.json();

        if (!respuesta.ok) {
            throw new Error(datos.error || 'No se pudo eliminar el producto.');
        }

        mostrarMensaje('Producto eliminado correctamente.');

        await cargarProductos();

    } catch (error) {
        console.error(error);
        mostrarMensaje(error.message, true);
    }
}

btnCancelar.addEventListener('click', limpiarFormulario);

btnActualizar.addEventListener('click', cargarProductos);

btnLogout.addEventListener('click', async () => {
    try {
        await fetch('/api/logout', {
            method: 'POST'
        });
    } finally {
        window.location.href = '/';
    }
});

function limpiarFormulario() {
    productoForm.reset();

    document.getElementById('productoId').value = '';

    tituloFormulario.textContent = 'Agregar producto';
    btnGuardar.textContent = 'Guardar producto';
    btnCancelar.classList.add('hidden');
}

function mostrarMensaje(texto, error = false) {
    mensajeProducto.textContent = texto;
    mensajeProducto.className =
        error ? 'mensaje error' : 'mensaje success';

    setTimeout(() => {
        mensajeProducto.textContent = '';
        mensajeProducto.className = 'mensaje';
    }, 4000);
}

function escapeHtml(texto) {
    const div = document.createElement('div');
    div.textContent = texto;
    return div.innerHTML;
}

(async function iniciar() {
    const autenticado = await comprobarSesion();

    if (autenticado) {
        await cargarProductos();
    }
})();
