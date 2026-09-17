const formulario = document.getElementById('loginForm');
const mensaje = document.getElementById('mensaje');

formulario.addEventListener('submit', async (event) => {
    event.preventDefault();

    const usuario = document.getElementById('usuario').value.trim();
    const password = document.getElementById('password').value;

    mensaje.textContent = 'Iniciando sesión...';
    mensaje.className = 'mensaje';

    try {
        const respuesta = await fetch('/api/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ usuario, password })
        });

        const datos = await respuesta.json();

        if (!respuesta.ok) {
            mensaje.textContent = datos.error || 'No se pudo iniciar sesión.';
            mensaje.className = 'mensaje error';
            return;
        }

        window.location.href = '/app';

    } catch (error) {
        console.error(error);

        mensaje.textContent = 'No se pudo conectar con el servidor.';
        mensaje.className = 'mensaje error';
    }
});
