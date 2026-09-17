-- Base de datos de Papelería ABC
-- El servidor Node.js puede crear automáticamente esta base,
-- tablas y datos de ejemplo al ejecutar npm start.

CREATE DATABASE IF NOT EXISTS papeleria
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE papeleria;

CREATE TABLE IF NOT EXISTS productos (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nombre VARCHAR(100) NOT NULL,
    categoria VARCHAR(50) NOT NULL,
    precio DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL
);

CREATE TABLE IF NOT EXISTS usuarios (
    id INT AUTO_INCREMENT PRIMARY KEY,
    usuario VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    nombre VARCHAR(100) NOT NULL,
    rol VARCHAR(30) NOT NULL DEFAULT 'Administrador'
);

-- La contraseña del usuario admin NO se coloca aquí en texto plano.
-- Node.js crea el usuario con bcrypt al ejecutar npm start.
