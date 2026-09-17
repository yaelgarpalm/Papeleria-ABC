const express = require('express');
const mysql = require('mysql2/promise');
const session = require('express-session');
const bcrypt = require('bcryptjs');
const path = require('path');

const app = express();
const PORT = 3000;

// ======================================================
// CONFIGURACIÓN DE MYSQL
// ======================================================
// Cambia únicamente DB_PASSWORD por la contraseña de root
// que configuraste al instalar MySQL Server.
// ======================================================

const DB_HOST = 'localhost';
const DB_USER = 'root';
const DB_PASSWORD = '123456789';
const DB_NAME = 'papeleria';

let db;

// ======================================================
// MIDDLEWARE
// ======================================================

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use(session({
    secret: 'papeleria-abc-clave-sesion-2026',
    resave: false,
    saveUninitialized: false,
    cookie: {
        httpOnly: true,
        maxAge: 60 * 60 * 1000
    }
}));

app.use('/static', express.static(path.join(__dirname, 'public')));

// ======================================================
// INICIALIZACIÓN DE BASE DE DATOS
// ======================================================

async function inicializarBaseDatos() {
    // Conexión inicial sin seleccionar una base de datos.
    const conexionInicial = await mysql.createConnection({
        host: DB_HOST,
        user: DB_USER,
        password: DB_PASSWORD
    });

    await conexionInicial.query(
        `CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`
    );

    await conexionInicial.end();

    db = await mysql.createPool({
        host: DB_HOST,
        user: DB_USER,
        password: DB_PASSWORD,
        database: DB_NAME,
        waitForConnections: true,
        connectionLimit: 10,
        queueLimit: 0
    });

    await db.execute(`
        CREATE TABLE IF NOT EXISTS productos (
            id INT AUTO_INCREMENT PRIMARY KEY,
            nombre VARCHAR(100) NOT NULL,
            categoria VARCHAR(50) NOT NULL,
            precio DECIMAL(10,2) NOT NULL,
            stock INT NOT NULL
        )
    `);

    await db.execute(`
        CREATE TABLE IF NOT EXISTS usuarios (
            id INT AUTO_INCREMENT PRIMARY KEY,
            usuario VARCHAR(50) NOT NULL UNIQUE,
            password VARCHAR(255) NOT NULL,
            nombre VARCHAR(100) NOT NULL,
            rol VARCHAR(30) NOT NULL DEFAULT 'Administrador'
        )
    `);

    // Usuario administrador inicial.
    const [usuarios] = await db.execute(
        'SELECT id FROM usuarios WHERE usuario = ?',
        ['admin']
    );

    if (usuarios.length === 0) {
        const passwordHash = await bcrypt.hash('Admin123', 10);

        await db.execute(
            `INSERT INTO usuarios (usuario, password, nombre, rol)
             VALUES (?, ?, ?, ?)`,
            ['admin', passwordHash, 'Administrador', 'Administrador']
        );

        console.log('Usuario inicial creado:');
        console.log('  Usuario: admin');
        console.log('  Contraseña: Admin123');
    }

    // Datos de ejemplo. Solo se insertan si la tabla está vacía.
    const [productos] = await db.execute(
        'SELECT COUNT(*) AS total FROM productos'
    );

    if (productos[0].total === 0) {
        const datos = [
            ['Cuaderno profesional', 'Cuadernos', 45.50, 25],
            ['Lápiz HB', 'Escritura', 6.00, 100],
            ['Pluma azul', 'Escritura', 8.50, 80],
            ['Borrador blanco', 'Papelería', 5.00, 50],
            ['Caja de colores', 'Colores', 65.00, 15],
            ['Regla 30 cm', 'Geometría', 18.00, 30]
        ];

        await db.query(
            `INSERT INTO productos
             (nombre, categoria, precio, stock)
             VALUES ?`,
            [datos]
        );

        console.log('Productos de ejemplo insertados.');
    }

    console.log('Base de datos y tablas verificadas.');
}

// ======================================================
// AUTENTICACIÓN
// ======================================================

function verificarSesion(req, res, next) {
    if (req.session.usuario) {
        return next();
    }

    res.status(401).json({
        error: 'No autorizado'
    });
}

// ======================================================
// PÁGINAS
// ======================================================

app.get('/', (req, res) => {
    if (req.session.usuario) {
        return res.redirect('/app');
    }

    res.sendFile(path.join(__dirname, 'public', 'login.html'));
});

app.get('/app', (req, res) => {
    if (!req.session.usuario) {
        return res.redirect('/');
    }

    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// ======================================================
// LOGIN
// ======================================================

app.post('/api/login', async (req, res) => {
    try {
        const { usuario, password } = req.body;

        if (!usuario || !password) {
            return res.status(400).json({
                error: 'Usuario y contraseña son obligatorios.'
            });
        }

        const [usuarios] = await db.execute(
            'SELECT * FROM usuarios WHERE usuario = ?',
            [usuario]
        );

        if (usuarios.length === 0) {
            return res.status(401).json({
                error: 'Usuario o contraseña incorrectos.'
            });
        }

        const usuarioBD = usuarios[0];

        const passwordCorrecta = await bcrypt.compare(
            password,
            usuarioBD.password
        );

        if (!passwordCorrecta) {
            return res.status(401).json({
                error: 'Usuario o contraseña incorrectos.'
            });
        }

        req.session.usuario = {
            id: usuarioBD.id,
            usuario: usuarioBD.usuario,
            nombre: usuarioBD.nombre,
            rol: usuarioBD.rol
        };

        res.json({
            mensaje: 'Inicio de sesión correcto.',
            usuario: req.session.usuario
        });

    } catch (error) {
        console.error('Error en login:', error);

        res.status(500).json({
            error: 'Error interno del servidor.'
        });
    }
});

// ======================================================
// LOGOUT
// ======================================================

app.post('/api/logout', (req, res) => {
    req.session.destroy((error) => {
        if (error) {
            console.error('Error al cerrar sesión:', error);

            return res.status(500).json({
                error: 'No se pudo cerrar la sesión.'
            });
        }

        res.clearCookie('connect.sid');

        res.json({
            mensaje: 'Sesión cerrada correctamente.'
        });
    });
});

// ======================================================
// SESIÓN ACTUAL
// ======================================================

app.get('/api/sesion', (req, res) => {
    if (!req.session.usuario) {
        return res.json({
            autenticado: false
        });
    }

    res.json({
        autenticado: true,
        usuario: req.session.usuario
    });
});

// ======================================================
// CRUD DE PRODUCTOS
// ======================================================

// Obtener todos
app.get('/api/productos', verificarSesion, async (req, res) => {
    try {
        const [productos] = await db.execute(
            'SELECT * FROM productos ORDER BY id'
        );

        res.json(productos);

    } catch (error) {
        console.error(error);

        res.status(500).json({
            error: 'Error al obtener productos.'
        });
    }
});

// Obtener uno
app.get('/api/productos/:id', verificarSesion, async (req, res) => {
    try {
        const [productos] = await db.execute(
            'SELECT * FROM productos WHERE id = ?',
            [req.params.id]
        );

        if (productos.length === 0) {
            return res.status(404).json({
                error: 'Producto no encontrado.'
            });
        }

        res.json(productos[0]);

    } catch (error) {
        console.error(error);

        res.status(500).json({
            error: 'Error al obtener el producto.'
        });
    }
});

// Crear
app.post('/api/productos', verificarSesion, async (req, res) => {
    try {
        const { nombre, categoria, precio, stock } = req.body;

        if (!nombre || !categoria || precio === undefined || stock === undefined) {
            return res.status(400).json({
                error: 'Todos los campos son obligatorios.'
            });
        }

        const precioNumero = Number(precio);
        const stockNumero = Number(stock);

        if (!Number.isFinite(precioNumero) || precioNumero < 0) {
            return res.status(400).json({
                error: 'El precio no es válido.'
            });
        }

        if (!Number.isInteger(stockNumero) || stockNumero < 0) {
            return res.status(400).json({
                error: 'El stock debe ser un número entero mayor o igual a 0.'
            });
        }

        const [resultado] = await db.execute(
            `INSERT INTO productos (nombre, categoria, precio, stock)
             VALUES (?, ?, ?, ?)`,
            [nombre.trim(), categoria.trim(), precioNumero, stockNumero]
        );

        res.status(201).json({
            id: resultado.insertId,
            nombre: nombre.trim(),
            categoria: categoria.trim(),
            precio: precioNumero,
            stock: stockNumero
        });

    } catch (error) {
        console.error(error);

        res.status(500).json({
            error: 'Error al crear el producto.'
        });
    }
});

// Actualizar
app.put('/api/productos/:id', verificarSesion, async (req, res) => {
    try {
        const { nombre, categoria, precio, stock } = req.body;

        if (!nombre || !categoria || precio === undefined || stock === undefined) {
            return res.status(400).json({
                error: 'Todos los campos son obligatorios.'
            });
        }

        const precioNumero = Number(precio);
        const stockNumero = Number(stock);

        if (!Number.isFinite(precioNumero) || precioNumero < 0) {
            return res.status(400).json({
                error: 'El precio no es válido.'
            });
        }

        if (!Number.isInteger(stockNumero) || stockNumero < 0) {
            return res.status(400).json({
                error: 'El stock debe ser un número entero mayor o igual a 0.'
            });
        }

        const [resultado] = await db.execute(
            `UPDATE productos
             SET nombre = ?, categoria = ?, precio = ?, stock = ?
             WHERE id = ?`,
            [
                nombre.trim(),
                categoria.trim(),
                precioNumero,
                stockNumero,
                req.params.id
            ]
        );

        if (resultado.affectedRows === 0) {
            return res.status(404).json({
                error: 'Producto no encontrado.'
            });
        }

        res.json({
            mensaje: 'Producto actualizado correctamente.'
        });

    } catch (error) {
        console.error(error);

        res.status(500).json({
            error: 'Error al actualizar el producto.'
        });
    }
});

// Eliminar
app.delete('/api/productos/:id', verificarSesion, async (req, res) => {
    try {
        const [resultado] = await db.execute(
            'DELETE FROM productos WHERE id = ?',
            [req.params.id]
        );

        if (resultado.affectedRows === 0) {
            return res.status(404).json({
                error: 'Producto no encontrado.'
            });
        }

        res.json({
            mensaje: 'Producto eliminado correctamente.'
        });

    } catch (error) {
        console.error(error);

        res.status(500).json({
            error: 'Error al eliminar el producto.'
        });
    }
});

// ======================================================
// INICIO
// ======================================================

async function iniciarServidor() {
    try {
        await inicializarBaseDatos();

        app.listen(PORT, '0.0.0.0', () => {
            console.log('');
            console.log('==========================================');
            console.log(' PAPELERÍA ABC - SERVIDOR NODE.JS');
            console.log('==========================================');
            console.log(` Puerto: ${PORT}`);
            console.log(' Dirección: http://0.0.0.0:3000');
            console.log(' Dominio: http://papeleriaabc.com');
            console.log('==========================================');
        });

    } catch (error) {
        console.error('');
        console.error('No se pudo iniciar la aplicación.');
        console.error('Verifica que MySQL esté iniciado y que');
        console.error('DB_PASSWORD en server.js sea correcta.');
        console.error('');
        console.error(error.message);
        process.exit(1);
    }
}

iniciarServidor();
